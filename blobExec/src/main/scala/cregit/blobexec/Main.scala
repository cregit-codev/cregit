package cregit.blobexec

import com.madgag.git.ThreadLocalObjectDatabaseResources
import com.madgag.git.bfg.cleaner.protection.ProtectedObjectCensus
import com.madgag.git.bfg.cleaner.{FormerCommitFooter, ObjectIdCleaner, RepoRewriter}
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

import java.nio.file.{Files, Paths}

/**
 * Drop-in replacement for the dmgerman/bfg-repo-cleaner `--blob-exec` use case
 * needed by cregit. Behaves like:
 *
 *   java -jar bfg-blobexec.jar <repo.git> <command> <fileMaskRegex>
 *
 * which is equivalent to the fork's:
 *
 *   java -jar bfg.jar --blob-exec:<command>=<fileMaskRegex> --no-blob-protection <repo.git>
 *
 * For each blob whose filename matches `fileMaskRegex`, runs `command` with
 *   - stdin  = blob bytes
 *   - env BFG_BLOB     = blob object id (40-hex)
 *   - env BFG_FILENAME = filename of the blob in this tree
 * and replaces the blob's content with `command`'s stdout. Non-zero exit or
 * identical-output leaves the blob unchanged.
 *
 * No-blob-protection is the only mode (matches what cregit's pipeline always
 * passes); we don't accept HEAD-protection flags because cregit doesn't use them.
 */
object Main {

  private val Usage =
    """Usage: blobExec <repo.git> <command> <fileMaskRegex>
      |
      |  <repo.git>       path to the bare git repo to rewrite (mutates in place)
      |  <command>        absolute path to the per-blob script to run
      |  <fileMaskRegex>  regex matched against each blob's filename (e.g. '\.[ch]$')
      |""".stripMargin

  def main(args: Array[String]): Unit = {
    if (args.length != 3) {
      System.err.println(Usage)
      sys.exit(1)
    }
    val repoPath = Paths.get(args(0))
    val command = args(1)
    val mask = args(2)

    if (!Files.isDirectory(repoPath)) {
      System.err.println(s"Error: repo path [$repoPath] is not a directory")
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

    println(s"blobExec: repo=$repoPath command=$command mask=$mask")

    val repo: FileRepository = FileRepositoryBuilder
      .create(repoPath.toFile)
      .asInstanceOf[FileRepository]

    val tlResources = new ThreadLocalObjectDatabaseResources(repo.getObjectDatabase)

    val cmdArg = command
    val modifier = new BlobExecModifier {
      override val command: String = cmdArg
      override val fileMask: String = mask
      override val threadLocalObjectDBResources: ThreadLocalObjectDatabaseResources = tlResources
    }

    val cleanerConfig = ObjectIdCleaner.Config(
      protectedObjectCensus = ProtectedObjectCensus.None,
      treeBlobsCleaners = Seq(modifier),
      // Append `Former-commit-id: <orig-sha>` to every rewritten commit's
      // message. cregit's remapCommits step relies on this footer to
      // reconstruct the cregit-cid → original-cid mapping; without it
      // commitmap is degenerate. Matches the dmgerman fork's default
      // (privateDataRemoval=false in upstream BFG's CLIConfig).
      commitNodeCleaners = Seq(FormerCommitFooter)
    )

    RepoRewriter.rewrite(repo, cleanerConfig)
    repo.close()
  }
}
