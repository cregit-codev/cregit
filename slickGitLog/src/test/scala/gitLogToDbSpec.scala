import org.scalatest.FunSuite

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent

import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import java.sql.DriverManager
import java.text.SimpleDateFormat
import java.util.{Date, TimeZone}

class gitLogToDbSpec extends FunSuite {

  test("remove_trailing_space strips exactly one trailing space") {
    assert(gitLogToDB.remove_trailing_space("Bob ") === "Bob")
    assert(gitLogToDB.remove_trailing_space("Bob") === "Bob")
    assert(gitLogToDB.remove_trailing_space("Bob  ") === "Bob ")
    assert(gitLogToDB.remove_trailing_space("Bob Smith") === "Bob Smith")
  }

  test("parseGraftLine maps '<child> <parent>' to (parent, 1, child)") {
    val child = "c" * 40
    val parent = "p" * 40
    assert(gitLogToDB.parseGraftLine(s"$child $parent") === (parent, 1, child))
  }

  def withTempRepo(testCode: (Git, File) => Unit): Unit = {
    val dir = Files.createTempDirectory("gitLogToDbSpec").toFile
    val git = Git.init.setDirectory(dir).call()
    try {
      testCode(git, dir)
    } finally {
      git.close()
      def rm(f: File): Unit = {
        if (f.isDirectory) f.listFiles.foreach(rm)
        f.delete()
      }
      rm(dir)
    }
  }

  def commitFile(git: Git, dir: File, name: String, content: String,
    who: PersonIdent, message: String) = {
    val out = new PrintWriter(new File(dir, name))
    out.print(content)
    out.close()
    git.add.addFilepattern(name).call()
    git.commit.setAuthor(who).setCommitter(who).setMessage(message).call()
  }

  val utc = TimeZone.getTimeZone("UTC")
  val aliceDate = new Date(1500000000000L)
  val bobDate = new Date(1500000600000L)
  val alice = new PersonIdent("Alice Coder ", "alice@example.com", aliceDate, utc)
  val bob = new PersonIdent("Bob Hacker", "bob@example.com", bobDate, utc)

  test("git_commits_iterator maps commits to the expected tuples") {
    withTempRepo { (git, dir) =>
      val c1 = commitFile(git, dir, "a.txt", "one\n", alice, "first commit")
      val c2 = commitFile(git, dir, "b.txt", "two\n", bob,
        "second commit\n\nSigned-off-by: Bob Hacker <bob@example.com>\n")

      val windows = gitLogToDB.git_commits_iterator(git).toList
      assert(windows.size === 1)

      val commits = windows(0).sortBy(_._1._4)
      assert(commits.size === 2)

      val (commit1, log1, parents1, footers1) = commits(0)
      val (commit2, log2, parents2, footers2) = commits(1)

      val dt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

      assert(commit1 === (c1.getName,
        "Alice Coder", "alice@example.com", dt.format(aliceDate),
        "Alice Coder", "alice@example.com", dt.format(aliceDate),
        "first commit", false))
      assert(log1 === (c1.getName, "first commit"))
      assert(parents1 === Seq())
      assert(footers1 === Seq())

      assert(commit2._1 === c2.getName)
      assert(commit2._8 === "second commit")
      assert(commit2._9 === false)
      assert(parents2 === Seq((c2.getName, 0, c1.getName)))
      assert(footers2 === Seq((c2.getName, 0, "Signed-off-by",
        "Bob Hacker <bob@example.com>")))
    }
  }

  test("a merge commit has ismerge true and two indexed parents") {
    withTempRepo { (git, dir) =>
      val base = commitFile(git, dir, "a.txt", "one\n", alice, "base")
      git.checkout.setCreateBranch(true).setName("side").call()
      val side = commitFile(git, dir, "b.txt", "two\n", bob, "side")
      git.checkout.setName("master").call()
      val main = commitFile(git, dir, "c.txt", "three\n", alice, "main")
      val merge = git.merge.include(side).call().getNewHead

      val all = gitLogToDB.git_commits_iterator(git).toList.flatten
      val mergeTuple = all.find(_._1._1 == merge.getName).get

      assert(mergeTuple._1._9 === true)
      assert(mergeTuple._3 === Seq(
        (merge.getName, 0, main.getName),
        (merge.getName, 1, side.getName)))
    }
  }

  test("findGrafts parses info/grafts in a non-bare repo") {
    withTempRepo { (git, dir) =>
      val child = "1" * 40
      val parent = "2" * 40
      new File(dir, ".git/info").mkdirs()
      val out = new PrintWriter(new File(dir, ".git/info/grafts"))
      out.println(s"$child $parent")
      out.close()

      assert(gitLogToDB.findGrafts(dir.getPath, git) === List((parent, 1, child)))
    }
  }

  test("findGrafts returns the empty list when no grafts file exists") {
    withTempRepo { (git, dir) =>
      assert(gitLogToDB.findGrafts(dir.getPath, git) === List())
    }
  }

  test("isBare is false for a working-tree repo") {
    withTempRepo { (git, dir) =>
      assert(gitLogToDB.isBare(git) === false)
    }
  }

  test("main writes the complete commit metadata schema to SQLite") {
    withTempRepo { (git, dir) =>
      val c1 = commitFile(git, dir, "a.txt", "one\n", alice, "first commit")
      val c2 = commitFile(
        git,
        dir,
        "b.txt",
        "two\n",
        bob,
        "second commit\n\nSigned-off-by: Bob Hacker <bob@example.com>\n")
      val dbFile = new File(dir, "history.db")

      gitLogToDB.main(Array(dbFile.getPath, dir.getPath))

      Class.forName("org.sqlite.JDBC")
      val connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getPath)
      try {
        def intQuery(sql: String): Int = {
          val statement = connection.createStatement()
          try {
            val rows = statement.executeQuery(sql)
            try { rows.next(); rows.getInt(1) } finally rows.close()
          } finally statement.close()
        }

        def stringQuery(sql: String, parameter: String): String = {
          val statement = connection.prepareStatement(sql)
          try {
            statement.setString(1, parameter)
            val rows = statement.executeQuery()
            try { assert(rows.next()); rows.getString(1) } finally rows.close()
          } finally statement.close()
        }

        assert(intQuery("select count(*) from commits") === 2)
        assert(intQuery("select count(*) from parents") === 1)
        assert(intQuery("select count(*) from logs") === 2)
        assert(intQuery("select count(*) from footers") === 1)
        assert(stringQuery("select summary from commits where cid = ?", c2.getName) === "second commit")
        assert(stringQuery("select parent from parents where cid = ?", c2.getName) === c1.getName)
        assert(stringQuery("select log from logs where cid = ?", c2.getName).contains("Signed-off-by"))
      } finally connection.close()
    }
  }
}
