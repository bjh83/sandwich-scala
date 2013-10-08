package sandwich.client.filewatcher

import java.nio.file._
import java.nio.file.StandardWatchEventKinds._
import java.nio.file.LinkOption._
import scala.collection.mutable
import java.nio.file.attribute.BasicFileAttributes
import sandwich.client.fileindex.{FileItem, FileIndex}
import scala.collection.convert.Wrappers.JListWrapper
import sandwich.client.filewatcher.DirectoryWatcher.{FileHashRequest, FileIndexRequest}
import sandwich.controller
import java.io.File
import sandwich.utils.Settings
import akka.actor._
import akka.agent.Agent
import scala.collection.convert.Wrappers.JListWrapper
import akka.actor.ActorIdentity

/**
 * Created with IntelliJ IDEA.
 * User: brendan
 * Date: 9/16/13
 * Time: 4:28 PM
 * To change this template use File | Settings | File Templates.
 */
class DirectoryWatcher(val rootDirectory: Path) extends Actor {
  import context._
  private var fileIndex = FileIndex(Set[FileItem]())
  private val isRunning = Agent[Boolean](true)
  private val subscribers = mutable.Set[ActorRef]()

  override def preStart {
    DirectoryWatcherCore.start
  }

  override def postStop {
    isRunning.send(false) // Kill the DirectoryWatcherCore.
  }

  override def receive = {
    case FileIndexRequest => sender ! fileIndex
    case FileHashRequest => sender ! fileIndex.IndexHash
    case newFileIndex: FileIndex => {
      fileIndex = newFileIndex
      subscribers.foreach(_ ! fileIndex)
      println(fileIndex)
    }
    case ActorIdentity(_, actorOption) => actorOption.foreach {
      actor => watch(actor)
      subscribers += actor
    }
    case Terminated(actorRef) => subscribers -= actorRef
  }

  private object DirectoryWatcherCore extends Thread {
    private val watcher = FileSystems.getDefault.newWatchService
    private val fileWatcherMap = mutable.Map[WatchKey, Path]()
    private val fileSet = mutable.Set[String]()
    registerAll(rootDirectory)
    updateFileIndex

    private def register(dir: Path) {
      val key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
      fileWatcherMap(key) = dir
    }

    private def registerAll(root: Path) {
      Files.walkFileTree(root, new DirectoryWatcherVisitor)
    }

    private class DirectoryWatcherVisitor extends SimpleFileVisitor[Path] {
      override def preVisitDirectory(dir: Path, attributes: BasicFileAttributes) = {
        register(dir)
        fileSet ++= dir.toFile.listFiles.filter(!_.isDirectory).map(_.getAbsolutePath)
        FileVisitResult.CONTINUE
      }
    }

    private def updateFileIndex {
      // TODO: This is kind of a mess, should probably fix it.
      // TODO: The checksum in fileItem is set to zero to match the canonical version; nevertheless, we should fix this.
      self ! FileIndex(fileSet.map(fileName => FileItem(fileName.replaceFirst(Settings.getSettings.sandwichPath + File.separator, ""), new File(fileName).length, 0)).toSet)
    }

    override def run {
      while(isRunning()) {
        try {
          val key = watcher.take
          val path = fileWatcherMap(key)
          for (raw_event <- JListWrapper[WatchEvent[_]](key.pollEvents())) {
            if(raw_event != OVERFLOW) {
              val event = raw_event.asInstanceOf[WatchEvent[Path]]
              val kind = event.kind
              val name = event.context
              val child = path.resolve(name)
              if (kind == ENTRY_CREATE) {
                if(Files.isDirectory(child, NOFOLLOW_LINKS)) {
                  registerAll(child)
                } else {
                  fileSet.add(child.toString)
                }
              }
              if (!key.reset) {
                fileWatcherMap.remove(key)
                fileSet --= path.toFile.list
              }
            }
          }
        } catch {
          case e: Exception => println("Error")
        }
        updateFileIndex
      }
    }
  }
}

object DirectoryWatcher {
  abstract class Request extends controller.Request

  object FileIndexRequest extends DirectoryWatcher.Request

  object FileHashRequest extends DirectoryWatcher.Request

  def props(rootDirectory: Path) = Props(classOf[DirectoryWatcher], rootDirectory)
}