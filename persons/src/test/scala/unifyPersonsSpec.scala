import org.scalatest.FunSuite
import unifyPersons.Person

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.apache.poi.hssf.usermodel.HSSFWorkbook

import java.io.{File, FileInputStream, PrintWriter}
import java.nio.file.Files
import java.sql.DriverManager
import java.util.{Date, TimeZone}

class unifyPersonsSpec extends FunSuite {

  def mkPerson(name: String, email: String): Person = {
    val (user, domain) = unifyPersons.splitEmail(email)
    val key = unifyPersons.dealWithSingleWords(name, email)
    new Person(name, key, email, email.toLowerCase, user, domain)
  }

  test("strip_accents removes combining diacritical marks") {
    assert(unifyPersons.strip_accents("José") === "Jose")
    assert(unifyPersons.strip_accents("Éléonore Müller") === "Eleonore Muller")
  }

  test("strip_accents maps the special characters ø ß æ ð") {
    assert(unifyPersons.strip_accents("Løvberg") === "Lovberg")
    assert(unifyPersons.strip_accents("Straße") === "Strasse")
    assert(unifyPersons.strip_accents("æ") === "ae")
    assert(unifyPersons.strip_accents("ð") === "o")
  }

  test("strip_accents leaves plain ascii untouched") {
    assert(unifyPersons.strip_accents("John Smith") === "John Smith")
  }

  test("Persons with identical fields are equal and hash-equal") {
    val a = mkPerson("Jim Smith", "jim@example.com")
    val b = mkPerson("Jim Smith", "jim@example.com")
    assert(a === b)
    assert(a.hashCode === b.hashCode)
  }

  test("Persons differing in email are not equal") {
    val a = mkPerson("Jim Smith", "jim@example.com")
    val b = mkPerson("Jim Smith", "jim@other.org")
    assert(a !== b)
  }

  test("Persons with colliding hashes but different fields are not equal") {
    val a = mkPerson("CCIB", "uhry@d49.test")
    val b = mkPerson("XVHD", "u18rx@d23.test")
    assert(a.hashCode === b.hashCode)
    assert(a !== b)
    assert(Set(a, b).size === 2)
  }

  test("equal Persons deduplicate in a Set") {
    val a = mkPerson("Jim Smith", "jim@example.com")
    val b = mkPerson("Jim Smith", "jim@example.com")
    val c = mkPerson("Ann Lee", "ann@example.com")
    assert(Set(a, b, c).size === 2)
  }

  test("splitEmail splits user and domain") {
    assert(unifyPersons.splitEmail("a@b.com") === ("a", "b.com"))
    assert(unifyPersons.splitEmail("Alice@Example.COM") === ("alice", "example.com"))
  }

  test("splitEmail without @ yields empty domain") {
    assert(unifyPersons.splitEmail("localonly") === ("localonly", ""))
  }

  test("a name with a space becomes the lowercased name") {
    assert(unifyPersons.dealWithSingleWords("Jim Smith", "jim@x.com") === "jim smith")
  }

  test("a single-word name falls back to 'name at addon'") {
    assert(unifyPersons.dealWithSingleWords("root", "root@x.com") === "root at root@x.com")
  }

  test("accents are stripped before building the key") {
    assert(unifyPersons.dealWithSingleWords("José Núñez", "jose@x.com") === "jose nunez")
  }

  test("unifyByEmail merges groups sharing an email, transitively") {
    val jim1 = mkPerson("Jim Smith", "jim@example.com")
    val jim2 = mkPerson("James Smith", "jim@example.com")
    val jim3 = mkPerson("James Smith", "jsmith@work.org")
    val ann = mkPerson("Ann Lee", "ann@example.org")

    val groups = List(List(jim1), List(jim2, jim3), List(ann))

    val unified = unifyPersons.unifyByEmail(groups)

    assert(unified.size === 2)
    assert(unified.contains(Set(jim1, jim2, jim3)))
    assert(unified.contains(Set(ann)))
  }

  test("unifyByEmail keeps disjoint groups apart") {
    val a = mkPerson("Jim Smith", "jim@example.com")
    val b = mkPerson("Ann Lee", "ann@example.org")
    val unified = unifyPersons.unifyByEmail(List(List(a), List(b)))
    assert(unified === Set(Set(a), Set(b)))
  }

  test("unifyByEmail on empty input yields the empty set") {
    assert(unifyPersons.unifyByEmail(Nil) === Set.empty[Set[Person]])
  }

  test("preferredName prefers a name containing a space") {
    val p = mkPerson("Jim Smith", "jim@example.com")
    assert(unifyPersons.preferredName(List(p)) === "Jim Smith")
  }

  test("preferredName falls back to the email for single-word names") {
    val p = mkPerson("root", "root@example.com")
    assert(unifyPersons.preferredName(List(p)) === "root@example.com")
  }

  test("preferredName only considers the first (most used) identity") {
    val single = mkPerson("root", "root@example.com")
    val full = mkPerson("Rudy Root", "rudy@example.com")
    assert(unifyPersons.preferredName(List(single, full)) === "root@example.com")
  }

  def withTempRepo(testCode: (Git, File) => Unit): Unit = {
    val dir = Files.createTempDirectory("unifyPersonsSpec").toFile
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

  test("main writes the identity spreadsheet and persons database") {
    withTempRepo { (git, dir) =>
      val utc = TimeZone.getTimeZone("UTC")
      val alice = new PersonIdent(
        "Alice Coder",
        "Alice@Example.COM",
        new Date(1500000000000L),
        utc)
      val bob = new PersonIdent(
        "Bob Hacker",
        "bob@example.com",
        new Date(1500000600000L),
        utc)
      commitFile(git, dir, "a.txt", "one\n", alice, "first")
      commitFile(git, dir, "b.txt", "two\n", bob, "second")

      val spreadsheet = new File(dir, "persons.xls")
      val database = new File(dir, "persons.db")
      unifyPersons.main(Array(dir.getPath, spreadsheet.getPath, database.getPath))

      assert(spreadsheet.isFile)
      assert(database.isFile)

      val spreadsheetInput = new FileInputStream(spreadsheet)
      val workbook = new HSSFWorkbook(spreadsheetInput)
      try {
        assert(workbook.getNumberOfSheets === 2)

        val identities = workbook.getSheet("identities")
        assert(identities != null)
        assert(identities.getLastRowNum === 2)
        assert((0 to 8).map(identities.getRow(0).getCell(_).getStringCellValue) === Seq(
          "key", "lcname", "name", "email", "lcUserId", "lcDomain",
          "countAll", "countAuthored", "countCommitted"))
        assert((0 to 5).map(identities.getRow(1).getCell(_).getStringCellValue) === Seq(
          "alice coder", "alice coder", "Alice Coder", "Alice@Example.COM",
          "alice", "example.com"))
        assert((6 to 8).map(identities.getRow(1).getCell(_).getNumericCellValue) === Seq(2.0, 1.0, 1.0))
        assert((0 to 5).map(identities.getRow(2).getCell(_).getStringCellValue) === Seq(
          "bob hacker", "bob hacker", "Bob Hacker", "bob@example.com",
          "bob", "example.com"))
        assert((6 to 8).map(identities.getRow(2).getCell(_).getNumericCellValue) === Seq(2.0, 1.0, 1.0))

        val stats = workbook.getSheet("stats")
        assert(stats != null)
        assert(stats.getLastRowNum === 2)
        assert((0 to 5).map(stats.getRow(0).getCell(_).getStringCellValue) === Seq(
          "key", "preferred", "identCount", "allCount", "authoredCount", "committedCount"))
        assert((0 to 1).map(stats.getRow(1).getCell(_).getStringCellValue) === Seq(
          "alice coder", "Alice Coder"))
        assert((2 to 5).map(stats.getRow(1).getCell(_).getNumericCellValue) === Seq(1.0, 2.0, 1.0, 1.0))
        assert((0 to 1).map(stats.getRow(2).getCell(_).getStringCellValue) === Seq(
          "bob hacker", "Bob Hacker"))
        assert((2 to 5).map(stats.getRow(2).getCell(_).getNumericCellValue) === Seq(1.0, 2.0, 1.0, 1.0))
      } finally {
        workbook.close()
        spreadsheetInput.close()
      }

      Class.forName("org.sqlite.JDBC")
      val connection = DriverManager.getConnection("jdbc:sqlite:" + database.getPath)
      try {
        def intQuery(sql: String): Int = {
          val statement = connection.createStatement()
          try {
            val rows = statement.executeQuery(sql)
            try { rows.next(); rows.getInt(1) } finally rows.close()
          } finally statement.close()
        }

        val statement = connection.prepareStatement(
          "select autcount, comcount from emails where emailaddr = ?")
        try {
          statement.setString(1, "Alice@Example.COM")
          val rows = statement.executeQuery()
          try {
            assert(rows.next())
            assert(rows.getInt(1) === 1)
            assert(rows.getInt(2) === 1)
          } finally rows.close()
        } finally statement.close()

        assert(intQuery("select count(*) from emails") === 2)
        assert(intQuery("select count(*) from persons") === 2)
        assert(intQuery("select count(*) from persons where personid = 'alice coder'") === 1)
      } finally connection.close()
    }
  }
}
