package cregit.blobexec

import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

import java.nio.file.{Files, Paths}

/**
 * From-scratch rewriter for the cregit pipeline (step 2). Replaces the
 * previous in-place BFG-based rewrite with a jgit walk that builds a new
 * destination repo and persists `(orig → new)` mappings to SQLite, so a
 * subsequent invocation can resume incrementally.
 *
 *   blobExec [--abort-on-error] <src.git> <dst.git> <db.sqlite> <command> <fileMaskRegex>
 *
 *   --abort-on-error  exit immediately (status 2) on the first non-zero
 *                     exit from <command>, instead of skipping that blob
 *   <src.git>         path to the bare source repo (read-only)
 *   <dst.git>         path to the bare destination repo (created if missing)
 *   <db.sqlite>       path to the SQLite mapping file (created if missing)
 *   <command>         absolute path to the per-blob script to run
 *   <fileMaskRegex>   regex matched against each blob's filename
 *
 * For each blob whose filename matches `fileMaskRegex`, `command` is invoked
 * with the blob bytes on stdin and env vars `BFG_BLOB` (orig sha) +
 * `BFG_FILENAME`. Its stdout becomes the new blob. Non-zero exit or
 * identical-output leaves the blob unchanged. The new commit message gets
 * `Former-commit-id: <orig-sha>` appended (BFG-compatible).
 */
object Main {

  private val Usage =
    """Usage: blobExec [--abort-on-error] <src.git> <dst.git> <db.sqlite> <command> <fileMaskRegex>
      |
      |  --abort-on-error  exit immediately (status 2) on the first non-zero
      |                    exit from <command>, instead of skipping that blob
      |  <src.git>         bare source repo (read-only)
      |  <dst.git>         bare destination repo (created on first run, reused on incremental)
      |  <db.sqlite>       SQLite mapping file (created on first run, reused on incremental)
      |  <command>         absolute path to the per-blob script to run
      |  <fileMaskRegex>   regex matched against each blob's filename (e.g. '\.[ch]$')
      |""".stripMargin

  def main(args: Array[String]): Unit = {
    val (flags, positional) = args.partition(_.startsWith("-"))

    val abortOnError = flags.foldLeft(false) {
      case (_, "--abort-on-error") => true
      case (_, other) =>
        System.err.println(s"Error: unknown flag [$other]")
        System.err.println(Usage)
        sys.exit(1)
    }

    if (positional.length != 5) {
      System.err.println(Usage)
      sys.exit(1)
    }
    val srcPath = Paths.get(positional(0))
    val dstPath = Paths.get(positional(1))
    val dbPath  = Paths.get(positional(2))
    val command = positional(3)
    val mask    = positional(4)

    if (!Files.isDirectory(srcPath)) {
      System.err.println(s"Error: src repo [$srcPath] is not a directory")
      sys.exit(1)
    }
    if (!Files.exists(Paths.get(command))) {
      System.err.println(s"Error: command [$command] does not exist")
      sys.exit(1)
    }
    if (mask.isEmpty) {
      System.err.println("Error: fileMaskRegex must be non-empty")
      sys.exit(1)
    }
    // Files.createDirectories throws FileAlreadyExistsException on macOS
    // when the target is a symlink (e.g. /tmp -> /private/tmp). Guard
    // against that with an explicit isDirectory check.
    val dbParent = dbPath.getParent
    if (dbParent != null && !Files.isDirectory(dbParent)) Files.createDirectories(dbParent)

    val incremental = Files.isDirectory(dstPath)
    println(
      s"blobExec: src=$srcPath dst=$dstPath db=$dbPath command=$command mask=$mask " +
        s"abortOnError=$abortOnError incremental=$incremental"
    )

    val src: FileRepository = openSrc(srcPath)
    val dst: FileRepository = openOrInitDst(dstPath)
    val mapping = try Mapping.open(dbPath, command, mask) catch {
      case m: Mapping.MetaMismatchException =>
        System.err.println(s"Error: ${m.getMessage}")
        src.close(); dst.close()
        sys.exit(3)
    }

    val stats = try {
      val parallelism = math.max(1, Runtime.getRuntime.availableProcessors)
      val walker = new Walker(src, dst, mapping, mask.r, command, abortOnError, parallelism)
      walker.run()
    } finally {
      mapping.close()
      dst.close()
      src.close()
    }

    println(
      s"blobExec done: commitsProcessed=${stats.commitsProcessed} " +
        s"blobsRunThroughCommand=${stats.blobsRunThroughCommand} " +
        s"blobsCacheHit=${stats.blobsCacheHit} " +
        s"refsProjected=${stats.refsProjected} " +
        s"aborted=${stats.aborted}"
    )

    if (stats.aborted) sys.exit(2)
  }

  private def openSrc(path: java.nio.file.Path): FileRepository = {
    val gitDir = if (Files.isDirectory(path.resolve(".git"))) path.resolve(".git").toFile else path.toFile
    FileRepositoryBuilder.create(gitDir).asInstanceOf[FileRepository]
  }

  private def openOrInitDst(path: java.nio.file.Path): FileRepository = {
    val exists = Files.isDirectory(path)
    val repo = FileRepositoryBuilder.create(path.toFile).asInstanceOf[FileRepository]
    if (!exists) repo.create(true)  // bare init
    repo
  }
}
