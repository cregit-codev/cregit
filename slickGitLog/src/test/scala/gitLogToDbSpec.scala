/*

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

*/

import org.scalatest.FunSuite

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent

import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.{Date, TimeZone}

class gitLogToDbSpec extends FunSuite {

  test("remove_trailing_space strips exactly one trailing space") {
    assert(gitLogToDB.remove_trailing_space("Bob ") === "Bob")
    assert(gitLogToDB.remove_trailing_space("Bob") === "Bob")
    // regex is " $": only the last space is removed
    assert(gitLogToDB.remove_trailing_space("Bob  ") === "Bob ")
    // inner spaces are kept
    assert(gitLogToDB.remove_trailing_space("Bob Smith") === "Bob Smith")
  }

  test("parseGraftLine maps '<child> <parent>' to (parent, 1, child)") {
    val child = "c" * 40
    val parent = "p" * 40
    assert(gitLogToDB.parseGraftLine(s"$child $parent") === (parent, 1, child))
  }

  // --- fixture helpers -----------------------------------------------------

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
  // author name with a trailing space, to exercise remove_trailing_space
  val alice = new PersonIdent("Alice Coder ", "alice@example.com", aliceDate, utc)
  val bob = new PersonIdent("Bob Hacker", "bob@example.com", bobDate, utc)

  // -------------------------------------------------------------------------

  test("git_commits_iterator maps commits to the expected tuples") {
    withTempRepo { (git, dir) =>
      val c1 = commitFile(git, dir, "a.txt", "one\n", alice, "first commit")
      val c2 = commitFile(git, dir, "b.txt", "two\n", bob,
        "second commit\n\nSigned-off-by: Bob Hacker <bob@example.com>\n")

      val windows = gitLogToDB.git_commits_iterator(git).toList
      assert(windows.size === 1) // 2 commits fit in one window of commitsPerOp

      val commits = windows(0).sortBy(_._1._4) // order by author date
      assert(commits.size === 2)

      val (commit1, log1, parents1, footers1) = commits(0)
      val (commit2, log2, parents2, footers2) = commits(1)

      // expected dates computed with the same formatter over the same Date,
      // so the assertion is immune to the JVM default timezone
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

      assert(mergeTuple._1._9 === true) // ismerge
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
}
