package polynote.server

import java.io.File
import java.nio.file.{AccessDeniedException, FileAlreadyExistsException}
import java.util.concurrent.TimeUnit

import cats.effect.ConcurrentEffect
import polynote.kernel.environment.Config
import polynote.kernel.{BaseEnv, GlobalEnv, KernelBusyState, LocalKernel}
import polynote.kernel.util.RefMap
import polynote.messages.{CreateNotebook, DeleteNotebook, Message, Notebook, NotebookUpdate, RenameNotebook, ShortString}
import polynote.server.repository.NotebookRepository
import polynote.server.repository.ipynb.IPythonNotebookRepository
import zio.blocking.Blocking
import zio.{Fiber, Promise, RIO, Task, UIO, ZIO}
import zio.interop.catz._
import KernelPublisher.SubscriberId
import fs2.concurrent.Topic
import polynote.kernel.logging.Logging

import scala.concurrent.duration.Duration

trait NotebookManager {
  val notebookManager: NotebookManager.Service
}

object NotebookManager {

  def access: RIO[NotebookManager, Service] = ZIO.access[NotebookManager](_.notebookManager)

  trait Service {
    def open(path: String): RIO[BaseEnv with GlobalEnv, KernelPublisher]
    def list(): RIO[BaseEnv with GlobalEnv, List[String]]
    def listRunning(): RIO[BaseEnv with GlobalEnv, List[String]]
    def status(path: String): RIO[BaseEnv with GlobalEnv, KernelBusyState]
    def create(path: String, maybeUriOrContent: Option[Either[String, String]]): RIO[BaseEnv with GlobalEnv, String]
    def rename(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String]
    def delete(path: String): RIO[BaseEnv with GlobalEnv, Unit]
  }

  object Service {

    def apply(repository: NotebookRepository[RIO[BaseEnv, ?]], broadcastAll: Topic[Task, Option[Message]]): RIO[BaseEnv, Service] =
      repository.initStorage() *> RefMap.empty[String, (KernelPublisher, NotebookWriter)].map {
        openNotebooks => new Impl(openNotebooks, repository, broadcastAll)
      }

    private case class NotebookWriter(fiber: Fiber[Throwable, Unit], shutdownSignal: Promise[Throwable, Unit]) {
      def stop(): Task[Unit] = shutdownSignal.succeed(()) *> fiber.join
    }

    private class Impl(
      openNotebooks: RefMap[String, (KernelPublisher, NotebookWriter)],
      repository: NotebookRepository[RIO[BaseEnv, ?]],
      broadcastAll: Topic[Task, Option[Message]]
    ) extends Service {

      // write the notebook every 1 second, if it's changed.
      private def startWriter(publisher: KernelPublisher): ZIO[BaseEnv, Nothing, NotebookWriter] = for {
        shutdownSignal <- Promise.make[Throwable, Unit]
        fiber          <- publisher.notebooksTimed(Duration(1, TimeUnit.SECONDS))
          .evalMap(notebook => repository.saveNotebook(notebook.path, notebook))
          .interruptWhen(shutdownSignal.await.either)
          .interruptWhen(publisher.closed.await.either)
          .compile.drain.fork
      } yield NotebookWriter(fiber, shutdownSignal)

      override def open(path: String): RIO[BaseEnv with GlobalEnv, KernelPublisher] = openNotebooks.getOrCreate(path) {
        for {
          notebook      <- repository.loadNotebook(path)
          publisher     <- KernelPublisher(notebook)
          writer        <- startWriter(publisher)
          onClose       <- publisher.closed.await.flatMap(_ => openNotebooks.remove(path)).fork
        } yield (publisher, writer)
      }.flatMap {
        case (publisher, writer) => publisher.closed.isDone.flatMap {
          case true  => open(path)
          case false => ZIO.succeed(publisher)
        }
      }

      override def list(): RIO[BaseEnv, List[String]] = repository.listNotebooks()

      override def listRunning(): RIO[BaseEnv, List[String]] = openNotebooks.keys

      override def create(path: String, maybeUriOrContent: Option[Either[String, String]]): RIO[BaseEnv, String] =
        repository.createNotebook(path, maybeUriOrContent).flatMap {
          actualPath => (broadcastAll.publish1(Some(CreateNotebook(ShortString(actualPath)))) *> broadcastAll.publish1(None)).as(actualPath)
        }

      override def rename(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String] =
        openNotebooks.get(path).flatMap {
          case None                      => repository.renameNotebook(path, newPath)
          case Some((publisher, writer)) => repository.notebookExists(newPath).flatMap {
            case true  => ZIO.fail(new FileAlreadyExistsException(s"File $newPath already exists"))
            case false => // if the notebook is already open, we have to stop writing, rename, and start writing again
              writer.stop() *> repository.renameNotebook(path, newPath).foldM(
                err => startWriter(publisher) *> Logging.error("Unable to rename notebook", err) *> ZIO.fail(err),
                realPath => publisher.rename(realPath).as(realPath) *> startWriter(publisher).flatMap {
                  writer => openNotebooks.put(path, (publisher, writer)).as(realPath)
                }
              )
          }
        }.flatMap {
          realPath => broadcastAll.publish1(Some(RenameNotebook(path, realPath))).as(realPath) <* broadcastAll.publish1(None)
        }

      override def delete(path: String): RIO[BaseEnv with GlobalEnv, Unit] =
        openNotebooks.get(path).flatMap {
          case Some(_) => ZIO.fail(new AccessDeniedException(path, null, "Notebook cannot be deleted while it is open"))
          case None    => repository.deleteNotebook(path) *> broadcastAll.publish1(Some(DeleteNotebook(path))) *> broadcastAll.publish1(None)
        }

      override def status(path: String): RIO[BaseEnv with GlobalEnv, KernelBusyState] = openNotebooks.get(path).flatMap {
        case None => ZIO.succeed(KernelBusyState(busy = false, alive = false))
        case Some((publisher, _)) => publisher.kernelStatus()
      }
    }
  }

  def apply(broadcastAll: Topic[Task, Option[Message]])(implicit ev: ConcurrentEffect[RIO[BaseEnv, ?]]): RIO[BaseEnv with GlobalEnv, NotebookManager] = for {
    config    <- Config.access
    blocking  <- ZIO.accessM[Blocking](_.blocking.blockingExecutor)
    repository = new IPythonNotebookRepository[RIO[BaseEnv, ?]](
      new File(System.getProperty("user.dir")).toPath.resolve(config.storage.dir),
      config,
      executionContext = blocking.asEC
    )
    service   <- Service(repository, broadcastAll)
  } yield new NotebookManager {
    val notebookManager: Service = service
  }
}
