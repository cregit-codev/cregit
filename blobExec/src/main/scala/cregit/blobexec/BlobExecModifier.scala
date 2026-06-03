package cregit.blobexec

import com.google.common.io.ByteStreams
import com.madgag.git.ThreadLocalObjectDatabaseResources
import com.madgag.git.bfg.cleaner.TreeBlobModifier
import com.madgag.git.bfg.model.{BlobFileMode, TreeBlobEntry}
import org.eclipse.jgit.lib.Constants.OBJ_BLOB
import org.eclipse.jgit.lib.ObjectId

import java.io.{InputStream, OutputStream}
import java.util.{Arrays => JavaArrays}
import scala.collection.mutable.ArrayBuffer
import scala.sys.process.{Process, ProcessIO}

/**
 * Per-blob modifier: pipes blob bytes through an external command and replaces
 * the blob with the command's stdout. Filename-filtered by the regex `fileMask`.
 *
 * Behavioral contract reproduced from the dmgerman/bfg-repo-cleaner@blobexec fork
 * so the cregit pipeline's tokenize step can drop that fork:
 *   - env BFG_BLOB    = blob object id (40-hex)
 *   - env BFG_FILENAME = blob's filename in the tree (basename + ext)
 *   - stdin  = original blob bytes
 *   - stdout = replacement blob bytes
 *   - non-zero exit, identical-to-input output, or no-regex-match all leave the blob unchanged
 */
trait BlobExecModifier extends TreeBlobModifier {

  def command: String

  def fileMask: String

  val threadLocalObjectDBResources: ThreadLocalObjectDatabaseResources

  private lazy val toProcess = fileMask.r

  override def fix(entry: TreeBlobEntry): (BlobFileMode, ObjectId) = {
    val fileName = entry.filename.toString
    toProcess.findFirstIn(fileName) match {
      case Some(_) => execute(entry)
      case None    => entry.withoutName
    }
  }

  private def execute(entry: TreeBlobEntry): (BlobFileMode, ObjectId) = {
    val fileName = entry.filename.toString
    val loader = threadLocalObjectDBResources.reader().open(entry.objectId)
    val bytes = ByteStreams.toByteArray(loader.openStream())

    val newBytes = ArrayBuffer[Byte]()

    val readJob: InputStream => Unit = { in =>
      val buf = new Array[Byte](8192)
      var n = in.read(buf)
      while (n != -1) {
        newBytes.appendAll(buf.iterator.take(n))
        n = in.read(buf)
      }
      in.close()
    }

    val writeJob: OutputStream => Unit = { out =>
      out.write(bytes)
      out.close()
    }

    val io = new ProcessIO(writeJob, readJob, _ => (), false)

    val pb = Process(
      Seq(command),
      None,
      "BFG_BLOB"     -> entry.objectId.name,
      "BFG_FILENAME" -> fileName
    )
    val proc = pb.run(io)
    val exitCode = proc.exitValue()

    if (exitCode != 0) {
      println(s"Warning: error executing command [$command] on blob ${entry.objectId.name} with filename [$fileName]: exit code $exitCode")
      entry.withoutName
    } else if (JavaArrays.equals(bytes, newBytes.toArray)) {
      entry.withoutName
    } else {
      val objectId = threadLocalObjectDBResources
        .inserter()
        .insert(OBJ_BLOB, newBytes.toArray)
      TreeBlobEntry(entry.filename, entry.mode, objectId).withoutName
    }
  }
}
