package polynote.server

import java.io.{BufferedReader, File, FileInputStream, InputStreamReader}
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

import fs2.Chunk
import fs2.concurrent.Topic
import org.http4s.{Charset, Headers, HttpApp, HttpRoutes, MediaType, Request, Response, StaticFile}
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import polynote.buildinfo.BuildInfo
import polynote.config.PolynoteConfig
import polynote.kernel.environment.Env
import polynote.kernel.logging.Logging
import polynote.kernel.{BaseEnv, GlobalEnv, Kernel, interpreter}
import polynote.messages.Message
import zio.{Cause, RIO, Task, ZIO}
import zio.interop.catz._
import zio.interop.catz.implicits._
import zio.blocking.{Blocking, effectBlocking}

import scala.annotation.tailrec

class Server(kernelFactory: Kernel.Factory.Service) extends polynote.app.App with Http4sDsl[Task] {

  private val blockingEC = unsafeRun(Environment.blocking.blockingExecutor).asEC

  private def indexFileContent(key: String, config: PolynoteConfig, watchUI: Boolean) = {
    val is = ZIO {
      if (watchUI) {
        java.nio.file.Files.newInputStream(
          new File(System.getProperty("user.dir")).toPath.resolve(s"polynote-frontend/dist/index.html"))
      } else {
        getClass.getClassLoader.getResourceAsStream("index.html")
      }
    }

    val content = is.bracket(is => ZIO(is.close()).orDie) {
      is => effectBlocking(scala.io.Source.fromInputStream(is, "UTF-8").mkString
        .replace("$WS_KEY", key.toString)
        .replace("$BASE_URI", config.ui.baseUri))
    }.orDie

    content match {
      case content if watchUI => ZIO.succeed(content)
      case content            => content.memoize
    }
  }

  private val securityWarning =
    """Polynote allows arbitrary remote code execution, which is necessary for a notebook tool to function.
      |While we'll try to improve safety by adding security measures, it will never be completely safe to
      |run Polynote on your personal computer. For example:
      |
      |- It's possible that other websites you visit could use Polynote as an attack vector. Browsing the web
      |  while running Polynote is unsafe.
      |- It's possible that remote attackers could use Polynote as an attack vector. Running Polynote on a
      |  computer that's accessible from the internet is unsafe.
      |- Even running Polynote inside a container doesn't guarantee safety, as there will always be
      |  privilege escalation and container escape vulnerabilities which an attacker could leverage.
      |
      |Please be diligent about checking for new releases, as they could contain fixes for critical security
      |flaws.
      |
      |Please be mindful of the security issues that Polynote causes; consult your company's security team
      |before running Polynote. You are solely responsible for any breach, loss, or damage caused by running
      |this software insecurely.""".stripMargin

  override def reportFailure(cause: Cause[_]): Unit = cause.failures.distinct.filterNot(_ == EOF) match {
    case Nil => ()  // unable to otherwise silence this error that happens whenever websocket is closed by client
    case _   => super.reportFailure(cause)
  }

  override def run(args: List[String]): ZIO[Environment, Nothing, Int] = for {
    args         <- ZIO.fromEither(Server.parseArgs(args)).orDie
    _            <- Logging.info(s"Loading configuration from ${args.configFile}")
    config       <- PolynoteConfig.load(args.configFile).orDie
    port          = config.listen.port
    address       = config.listen.host
    wsKey         = config.security.websocketKey.getOrElse(UUID.randomUUID().toString)
    host          = if (address == "0.0.0.0") java.net.InetAddress.getLocalHost.getHostAddress else address
    url           = s"http://$host:$port"
    interps      <- interpreter.Loader.load.orDie
    globalEnv     = Env.enrichWith[BaseEnv, GlobalEnv](Environment, GlobalEnv(config, interps, kernelFactory))
    broadcastAll <- Topic[Task, Option[Message]](None).orDie
    manager      <- NotebookManager(broadcastAll).provide(globalEnv).orDie
    socketEnv     = Env.enrichWith[BaseEnv with GlobalEnv, NotebookManager](globalEnv, manager)
    loadIndex    <- indexFileContent(wsKey, config, args.watchUI)
    app          <- httpApp(args.watchUI, wsKey, loadIndex, broadcastAll).provide(socketEnv).orDie
    _            <- Logging.warn(securityWarning)
    exit         <- BlazeServerBuilder[Task]
      .withBanner(
        raw"""
             |
             |  _____      _                   _
             | |  __ \    | |                 | |
             | | |__) |__ | |_   _ _ __   ___ | |_ ___
             | |  ___/ _ \| | | | | '_ \ / _ \| __/ _ \
             | | |  | (_) | | |_| | | | | (_) | ||  __/
             | |_|   \___/|_|\__, |_| |_|\___/ \__\___|
             |                __/ |
             |               |___/
             |
             |Server running at $url
             |""".stripMargin.lines.toList
      )
      .bindHttp(port, address)
      .withWebSockets(true)
      .withHttpApp(app)
      .serve.compile.last.orDie
  } yield exit.map(_.code).getOrElse(0)


  private def staticFile(location: String, req: Request[Task]): Task[Response[Task]] =
    StaticFile.fromString[Task](location, blockingEC, Some(req)).getOrElseF(NotFound())

  private def staticResource(path: String, req: Request[Task]): Task[Response[Task]] =
    StaticFile.fromResource(path, blockingEC, Some(req)).getOrElseF(NotFound())

  def serveFile(path: String, req: Request[Task], watchUI: Boolean): Task[Response[Task]] = {
    if (watchUI) {
      val outputLoc = new File(System.getProperty("user.dir")).toPath.resolve(s"polynote-frontend/dist/$path").toString
      staticFile(outputLoc, req)
    } else {
      staticResource(path, req)
    }
  }

  def downloadFile(path: String, req: Request[Task], config: PolynoteConfig): Task[Response[Task]] = {
    val nbLoc = new File(System.getProperty("user.dir")).toPath.resolve(s"${config.storage.dir}/$path").toString
    staticFile(nbLoc, req)
  }

  object DownloadMatcher extends OptionalQueryParamDecoderMatcher[String]("download")
  object KeyMatcher extends QueryParamDecoderMatcher[String]("key")

  def httpApp(
    watchUI: Boolean,
    wsKey: String,
    getIndex: ZIO[Blocking, Nothing, String],
    broadcastAll: Topic[Task, Option[Message]]
  ): RIO[BaseEnv with GlobalEnv with NotebookManager, HttpApp[Task]] = {
    val indexResponse = getIndex.map {
      index =>
        val indexBytes = index.getBytes(StandardCharsets.UTF_8)
        Response[Task](
          headers = Headers.of(`Content-Type`(MediaType.text.html, Charset.`UTF-8`), `Content-Length`.unsafeFromLong(indexBytes.length)),
          body = fs2.Stream.chunk(Chunk.bytes(indexBytes))
        )
    }

    for {
      env <- ZIO.access[BaseEnv with GlobalEnv with NotebookManager](identity)
    } yield HttpRoutes.of[Task] {
      case req @ GET -> Root / "ws" :? KeyMatcher(`wsKey`)                  => SocketSession(broadcastAll).flatMap(_.toResponse).provide(env)
      case GET -> Root / "ws"                                               => Forbidden()
      case req @ GET -> Root                                                => indexResponse.provide(env)
      case req @ GET -> "notebook" /: path :? DownloadMatcher(Some("true")) => downloadFile(path.toList.mkString("/"), req, env.polynoteConfig)
      case req @ GET -> "notebook" /: _                                     => indexResponse.provide(env)
      case req @ GET -> (Root / "polynote-assembly.jar")                    => StaticFile.fromFile[Task](new File(getClass.getProtectionDomain.getCodeSource.getLocation.getPath), blockingEC).getOrElseF(NotFound())
      case req @ GET -> path                                                => serveFile(path.toString, req, watchUI)
    }.mapF(_.getOrElseF(NotFound()))
  }

}

object Server {
  case class Args(
    configFile: File = new File("config.yml"),
    watchUI: Boolean = false
  )

  private val serverClass = """polynote.server.(.*)""".r

  @tailrec
  private def parseArgs(args: List[String], current: Args = Args()): Either[Throwable, Args] = args match {
    case Nil => Right(current)
    case ("--config" | "-c") :: filename :: rest => parseArgs(rest, current.copy(configFile = new File(filename)))
    case ("--watch"  | "-w") :: rest => parseArgs(rest, current.copy(watchUI = true))
    case serverClass(_) :: rest => parseArgs(rest, current) // class name might be arg0 in some circumstances
    case other :: rest => Left(new IllegalArgumentException(s"Unknown argument $other"))
  }
}
