package cregit.blobexec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class MappingSpec extends AnyFunSuite with Matchers {

  private def withMapping[A](command: String = "/bin/cat", mask: String = ".*")(body: Mapping => A): A = {
    val m = Mapping.openInMemory(command, mask)
    try body(m) finally m.close()
  }

  test("schema is created and put/get round-trips work for all maps (keyed by full path)") {
    withMapping() { m =>
      m.getBlob("orig", "src/foo.c") shouldBe None
      m.putBlob("orig", "src/foo.c", "newblob")
      m.getBlob("orig", "src/foo.c") shouldBe Some("newblob")

      // Same orig blob under a different full path is a distinct row.
      m.putBlob("orig", "lib/foo.c", "otherblob")
      m.getBlob("orig", "lib/foo.c") shouldBe Some("otherblob")
      m.getBlob("orig", "src/foo.c") shouldBe Some("newblob")

      m.putCommit("origc", "newc")
      m.getCommit("origc") shouldBe Some("newc")

      m.putTree("origt", "newt")
      m.getTree("origt") shouldBe Some("newt")
    }
  }

  test("processed_at is stamped automatically and stays put on INSERT OR IGNORE") {
    import java.sql.DriverManager
    val tmp = Files.createTempFile("mapping-pa-", ".db")
    try {
      val m1 = Mapping.open(tmp, "/bin/cat", ".*")
      try m1.putBlob("orig", "src/x.c", "new") finally m1.close()

      // Read the timestamp directly via JDBC.
      val conn = DriverManager.getConnection(s"jdbc:sqlite:${tmp.toAbsolutePath}")
      try {
        val readAt: () => Long = () => {
          val rs = conn.createStatement().executeQuery(
            "SELECT processed_at FROM blob_map WHERE orig_blob='orig' AND path='src/x.c'")
          try { rs.next(); rs.getLong(1) } finally rs.close()
        }
        val first = readAt()
        first should be > 0L

        // Sleep past one second so a new strftime('%s','now') would differ,
        // then re-put. With INSERT OR IGNORE the row is preserved → same ts.
        Thread.sleep(1100)
        val m2 = Mapping.open(tmp, "/bin/cat", ".*")
        try m2.putBlob("orig", "src/x.c", "new") finally m2.close()
        readAt() shouldEqual first
      } finally conn.close()
    } finally Files.deleteIfExists(tmp)
  }

  test("inTx commits on success") {
    withMapping() { m =>
      m.inTx {
        m.putCommit("c1", "n1")
        m.putCommit("c2", "n2")
      }
      m.getCommit("c1") shouldBe Some("n1")
      m.getCommit("c2") shouldBe Some("n2")
    }
  }

  test("inTx rolls back on throw") {
    withMapping() { m =>
      m.putCommit("c0", "n0")
      val ex = intercept[RuntimeException] {
        m.inTx {
          m.putCommit("c1", "n1")
          throw new RuntimeException("boom")
        }
      }
      ex.getMessage shouldEqual "boom"
      m.getCommit("c0") shouldBe Some("n0")
      m.getCommit("c1") shouldBe None
    }
  }

  test("open on existing file is idempotent and preserves rows") {
    val tmp = Files.createTempFile("mapping-spec-", ".db")
    try {
      val m1 = Mapping.open(tmp, "/bin/cat", ".*")
      try {
        m1.putCommit("c1", "n1")
        m1.putBlob("b1", "x.c", "nb1")
      } finally m1.close()

      val m2 = Mapping.open(tmp, "/bin/cat", ".*")
      try {
        m2.getCommit("c1") shouldBe Some("n1")
        m2.getBlob("b1", "x.c") shouldBe Some("nb1")
      } finally m2.close()
    } finally Files.deleteIfExists(tmp)
  }

  test("meta mismatch on command throws") {
    val tmp = Files.createTempFile("mapping-spec-", ".db")
    try {
      val m1 = Mapping.open(tmp, "/bin/cat", ".*")
      m1.close()
      val ex = intercept[Mapping.MetaMismatchException] {
        Mapping.open(tmp, "/bin/echo", ".*")
      }
      ex.getMessage should include("command")
    } finally Files.deleteIfExists(tmp)
  }

  test("meta mismatch on mask throws") {
    val tmp = Files.createTempFile("mapping-spec-", ".db")
    try {
      val m1 = Mapping.open(tmp, "/bin/cat", ".*")
      m1.close()
      val ex = intercept[Mapping.MetaMismatchException] {
        Mapping.open(tmp, "/bin/cat", """\.c$""")
      }
      ex.getMessage should include("mask")
    } finally Files.deleteIfExists(tmp)
  }

  test("allCommitOrigShas returns all rows") {
    withMapping() { m =>
      m.putCommit("a", "x")
      m.putCommit("b", "y")
      m.putCommit("c", "z")
      m.allCommitOrigShas.toSet shouldEqual Set("a", "b", "c")
    }
  }
}
