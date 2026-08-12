import org.scalatest.FunSuite

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent

import java.io.{File, PrintWriter}
import java.nio.file.Files
import java.sql.DriverManager
import java.util.{Date, TimeZone}

class remapCommitsSpec extends FunSuite {

  val cid = "a" * 40
  val originalCid = "0123456789abcdef0123456789abcdef01234567"

  test("footer on the last line yields the original commit id") {
    val message = s"some subject\n\nFormer-commit-id: $originalCid"
    assert(remapCommits.extractOriginalCid(cid, message) === originalCid)
  }

  test("footer as the only line yields the original commit id") {
    val message = s"Former-commit-id: $originalCid"
    assert(remapCommits.extractOriginalCid(cid, message) === originalCid)
  }

  test("footer not on the last line falls back to cid") {
    val message = s"subject\nFormer-commit-id: $originalCid\ntrailing line"
    assert(remapCommits.extractOriginalCid(cid, message) === cid)
  }

  test("message without a footer falls back to cid") {
    assert(remapCommits.extractOriginalCid(cid, "just a subject") === cid)
  }

  test("empty message falls back to cid") {
    assert(remapCommits.extractOriginalCid(cid, "") === cid)
  }

  test("message of only newlines falls back to cid") {
    assert(remapCommits.extractOriginalCid(cid, "\n") === cid)
    assert(remapCommits.extractOriginalCid(cid, "\n\n\n") === cid)
  }

  test("uppercase hex footer is not recognized (regex is lowercase-only)") {
    val upper = originalCid.toUpperCase
    assert(remapCommits.extractOriginalCid(cid, s"Former-commit-id: $upper") === cid)
  }

  test("short sha in footer is not recognized") {
    val message = "Former-commit-id: 0123456789abcdef"
    assert(remapCommits.extractOriginalCid(cid, message) === cid)
  }

  def withTempRepo(testCode: (Git, File) => Unit): Unit = {
    val dir = Files.createTempDirectory("remapCommitsSpec").toFile
    val git = Git.init.setDirectory(dir).call()
    try {
      testCode(git, dir)
    } finally {
      git.close()
      def rm(file: File): Unit = {
        if (file.isDirectory) file.listFiles.foreach(rm)
        file.delete()
      }
      rm(dir)
    }
  }

  def commitFile(
    git: Git,
    dir: File,
    name: String,
    content: String,
    who: PersonIdent,
    message: String) = {
    val out = new PrintWriter(new File(dir, name))
    out.print(content)
    out.close()
    git.add.addFilepattern(name).call()
    git.commit.setAuthor(who).setCommitter(who).setMessage(message).call()
  }

  test("main persists rewritten-to-original mappings in SQLite") {
    withTempRepo { (git, dir) =>
      val who = new PersonIdent(
        "Alice Coder",
        "alice@example.com",
        new Date(1500000000000L),
        TimeZone.getTimeZone("UTC"))
      val unchanged = commitFile(git, dir, "a.txt", "one\n", who, "first")
      val rewritten = commitFile(
        git,
        dir,
        "b.txt",
        "two\n",
        who,
        s"second\n\nFormer-commit-id: $originalCid")
      val database = new File(dir, "commit-map.db")

      remapCommits.main(Array(database.getPath, dir.getPath))

      Class.forName("org.sqlite.JDBC")
      val connection = DriverManager.getConnection("jdbc:sqlite:" + database.getPath)
      try {
        def mappedCid(cid: String): String = {
          val statement = connection.prepareStatement(
            "select originalcid from commitmap where cid = ?")
          try {
            statement.setString(1, cid)
            val rows = statement.executeQuery()
            try { assert(rows.next()); rows.getString(1) } finally rows.close()
          } finally statement.close()
        }

        val statement = connection.createStatement()
        try {
          val rows = statement.executeQuery("select count(*) from commitmap")
          try { assert(rows.next()); assert(rows.getInt(1) === 2) } finally rows.close()
        } finally statement.close()

        assert(mappedCid(unchanged.getName) === unchanged.getName)
        assert(mappedCid(rewritten.getName) === originalCid)
      } finally connection.close()
    }
  }
}
