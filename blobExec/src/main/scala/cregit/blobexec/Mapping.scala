package cregit.blobexec

import java.nio.file.Path
import java.sql.{Connection, DriverManager, PreparedStatement}

/** SQLite-backed persistent mapping between original and rewritten object ids.
  * One JDBC connection confined to the walker thread (only the external command
  * runs in parallel). Each row is stamped `processed_at` (epoch seconds) so a run's
  * rows are queryable; non-matching blobs get identity rows (orig == new). */
final class Mapping private (conn: Connection, warm: Option[Connection]) extends AutoCloseable {

  // INSERT OR IGNORE so processed_at reflects first-write time, not last.
  // The data values are deterministic for a given (key, command, mask), so
  // silently keeping the original row on conflict is safe.
  private val selBlob   = conn.prepareStatement("SELECT new_blob FROM blob_map WHERE orig_blob = ? AND path = ?")
  private val insBlob   = conn.prepareStatement("INSERT OR IGNORE INTO blob_map(orig_blob, path, new_blob) VALUES (?, ?, ?)")
  private val selCommit = conn.prepareStatement("SELECT new_commit FROM commit_map WHERE orig_commit = ?")
  private val insCommit = conn.prepareStatement("INSERT OR IGNORE INTO commit_map(orig_commit, new_commit) VALUES (?, ?)")
  private val selTree   = conn.prepareStatement("SELECT new_tree FROM tree_map WHERE orig_tree = ?")
  private val insTree   = conn.prepareStatement("INSERT OR IGNORE INTO tree_map(orig_tree, new_tree) VALUES (?, ?)")

  // Read-only fallback on the optional warm DB (frozen prior memo): consulted only
  // for content-addressed blob_map/tree_map misses, never commit_map, never written.
  // A hit is the id a fresh tokenize would produce, so it only skips redone work.
  private val warmSelBlob = warm.map(_.prepareStatement("SELECT new_blob FROM blob_map WHERE orig_blob = ? AND path = ?"))
  private val warmSelTree = warm.map(_.prepareStatement("SELECT new_tree FROM tree_map WHERE orig_tree = ?"))
  // Refs: INSERT OR REPLACE because a ref at the same name can be retargeted
  // (branch moved, tag re-issued). The row should reflect the current dst
  // state, not the first-seen state. processed_at is updated accordingly.
  private val selRef    = conn.prepareStatement("SELECT kind, orig_target, new_target, orig_commit, new_commit FROM ref_map WHERE ref_name = ?")
  private val insRef    = conn.prepareStatement("INSERT OR REPLACE INTO ref_map(ref_name, kind, orig_target, new_target, orig_commit, new_commit, processed_at) VALUES (?, ?, ?, ?, ?, ?, CAST(strftime('%s','now') AS INTEGER))")
  private val delRef    = conn.prepareStatement("DELETE FROM ref_map WHERE ref_name = ?")
  private val selMeta   = conn.prepareStatement("SELECT value FROM meta WHERE key = ?")
  private val insMeta   = conn.prepareStatement("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)")

  def getBlob(origBlob: String, path: String): Option[String] =
    Mapping.selectString(selBlob, origBlob, path)
      .orElse(warmSelBlob.flatMap(st => Mapping.selectString(st, origBlob, path)))

  def putBlob(origBlob: String, path: String, newBlob: String): Unit =
    Mapping.execute(insBlob, origBlob, path, newBlob)

  def getCommit(origCommit: String): Option[String] =
    Mapping.selectString(selCommit, origCommit)

  def putCommit(origCommit: String, newCommit: String): Unit =
    Mapping.execute(insCommit, origCommit, newCommit)

  def getTree(origTree: String): Option[String] =
    Mapping.selectString(selTree, origTree)
      .orElse(warmSelTree.flatMap(st => Mapping.selectString(st, origTree)))

  def putTree(origTree: String, newTree: String): Unit =
    Mapping.execute(insTree, origTree, newTree)

  def getRef(refName: String): Option[Mapping.RefRow] = {
    selRef.setString(1, refName)
    val rs = selRef.executeQuery()
    try if (rs.next()) Some(Mapping.RefRow(
        refName    = refName,
        kind       = rs.getString(1),
        origTarget = rs.getString(2),
        newTarget  = rs.getString(3),
        origCommit = rs.getString(4),
        newCommit  = rs.getString(5)
      )) else None
    finally rs.close()
  }

  def putRef(row: Mapping.RefRow): Unit = {
    insRef.setString(1, row.refName)
    insRef.setString(2, row.kind)
    insRef.setString(3, row.origTarget)
    insRef.setString(4, row.newTarget)
    insRef.setString(5, row.origCommit)
    insRef.setString(6, row.newCommit)
    insRef.executeUpdate()
    ()
  }

  def deleteRef(refName: String): Unit = {
    delRef.setString(1, refName)
    delRef.executeUpdate()
    ()
  }

  /** All ref_names currently in ref_map. */
  def allRefNames: Vector[String] = {
    val st = conn.createStatement()
    try {
      val rs = st.executeQuery("SELECT ref_name FROM ref_map")
      try Iterator
        .continually(if (rs.next()) Some(rs.getString(1)) else None)
        .takeWhile(_.isDefined)
        .flatten
        .toVector
      finally rs.close()
    } finally st.close()
  }

  def getMeta(key: String): Option[String] =
    Mapping.selectString(selMeta, key)

  def setMeta(key: String, value: String): Unit =
    Mapping.execute(insMeta, key, value)

  /** All original-commit SHAs already recorded. Used to mark walk frontier. */
  def allCommitOrigShas: Vector[String] = {
    val st = conn.createStatement()
    try {
      val rs = st.executeQuery("SELECT orig_commit FROM commit_map")
      try Iterator
        .continually(if (rs.next()) Some(rs.getString(1)) else None)
        .takeWhile(_.isDefined)
        .flatten
        .toVector
      finally rs.close()
    } finally st.close()
  }

  /** Run `body` inside a transaction; commit on success, rollback on throw. */
  def inTx[A](body: => A): A = {
    conn.setAutoCommit(false)
    try {
      val result = body
      conn.commit()
      result
    } catch {
      case t: Throwable =>
        try conn.rollback() catch { case _: Throwable => () }
        throw t
    } finally {
      conn.setAutoCommit(true)
    }
  }

  override def close(): Unit = {
    (List(selBlob, insBlob, selCommit, insCommit, selTree, insTree,
          selRef, insRef, delRef, selMeta, insMeta) ++ warmSelBlob.toList ++ warmSelTree.toList)
      .foreach(s => try s.close() catch { case _: Throwable => () })
    conn.close()
    warm.foreach(w => try w.close() catch { case _: Throwable => () })
  }
}

object Mapping {

  /** Mismatch between the recorded command/mask and the values passed in. */
  final class MetaMismatchException(message: String) extends RuntimeException(message)

  // processed_at stored INTEGER (epoch seconds) for numeric filters. FK
  // ref_map.orig_commit -> commit_map ON DELETE CASCADE cleans up refs with their
  // commit; blob_map/tree_map have no FK (a blob/tree spans many commits).
  private val Schema = Seq(
    """CREATE TABLE IF NOT EXISTS commit_map (
      |  orig_commit  TEXT PRIMARY KEY,
      |  new_commit   TEXT    NOT NULL,
      |  processed_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s','now') AS INTEGER))
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS blob_map (
      |  orig_blob    TEXT    NOT NULL,
      |  path         TEXT    NOT NULL,
      |  new_blob     TEXT    NOT NULL,
      |  processed_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s','now') AS INTEGER)),
      |  PRIMARY KEY (orig_blob, path)
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS tree_map (
      |  orig_tree    TEXT PRIMARY KEY,
      |  new_tree     TEXT    NOT NULL,
      |  processed_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s','now') AS INTEGER))
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS ref_map (
      |  ref_name     TEXT PRIMARY KEY,
      |  kind         TEXT    NOT NULL CHECK (kind IN ('head','annotated_tag','lightweight_tag')),
      |  orig_target  TEXT    NOT NULL,
      |  new_target   TEXT    NOT NULL,
      |  orig_commit  TEXT    NOT NULL REFERENCES commit_map(orig_commit) ON DELETE CASCADE,
      |  new_commit   TEXT    NOT NULL,
      |  processed_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s','now') AS INTEGER))
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS meta (
      |  key   TEXT PRIMARY KEY,
      |  value TEXT NOT NULL
      |)""".stripMargin
  )

  // A ref_map row. kind in {head, annotated_tag, lightweight_tag}: for annotated
  // tags the target fields hold the tag-object SHAs (commit fields peeled);
  // otherwise target == commit.
  final case class RefRow(
      refName: String,
      kind: String,
      origTarget: String,
      newTarget: String,
      origCommit: String,
      newCommit: String
  )

  /** Configure a fresh connection: FK enforcement (per-connection), plus WAL +
    * synchronous=NORMAL to drop the per-commit fsync floor (~1.46M txns) and
    * busy_timeout for concurrent readers. Crash-safe for content-addressed data --
    * a lost WAL tail is re-derived on resume from the memo. */
  private def enableForeignKeys(conn: Connection): Unit = {
    val st = conn.createStatement()
    try {
      st.execute("PRAGMA foreign_keys = ON")
      st.execute("PRAGMA journal_mode = WAL")
      st.execute("PRAGMA synchronous = NORMAL")
      st.execute("PRAGMA busy_timeout = 30000")
    } finally st.close()
  }

  /** Open/create the SQLite DB at `path` (schema created if absent) and validate
    * `command`/`mask` against `meta`: mismatch throws MetaMismatchException,
    * absence records them. */
  def open(path: Path, command: String, mask: String, warm: Option[Path] = None): Mapping = {
    val url = s"jdbc:sqlite:${path.toAbsolutePath}"
    val conn = DriverManager.getConnection(url)
    enableForeignKeys(conn)
    val st = conn.createStatement()
    try Schema.foreach(st.execute) finally st.close()

    val warmConn = warm.map(openWarm)

    val m = new Mapping(conn, warmConn)
    checkOrSetMeta(m, "command", command)
    checkOrSetMeta(m, "mask",    mask)
    m
  }

  /** Open a frozen prior-run DB read-only (query_only + busy_timeout) as a lookup
    * fallback; never written by us, so concurrent shard readers are safe. */
  private def openWarm(path: Path): Connection = {
    val url = s"jdbc:sqlite:${path.toAbsolutePath}"
    val c = DriverManager.getConnection(url)
    val st = c.createStatement()
    try {
      st.execute("PRAGMA query_only = ON")
      st.execute("PRAGMA busy_timeout = 30000")
    } finally st.close()
    c
  }

  /** In-memory variant used by tests. */
  def openInMemory(command: String, mask: String): Mapping = {
    val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
    enableForeignKeys(conn)
    val st = conn.createStatement()
    try Schema.foreach(st.execute) finally st.close()
    val m = new Mapping(conn, None)
    checkOrSetMeta(m, "command", command)
    checkOrSetMeta(m, "mask",    mask)
    m
  }

  private def checkOrSetMeta(m: Mapping, key: String, value: String): Unit =
    m.getMeta(key) match {
      case Some(existing) if existing != value =>
        throw new MetaMismatchException(
          s"meta mismatch on '$key': stored='$existing', requested='$value'. " +
            "Refusing incremental run against a different command/mask."
        )
      case Some(_) => ()
      case None    => m.setMeta(key, value)
    }

  private def selectString(st: PreparedStatement, args: String*): Option[String] = {
    args.zipWithIndex.foreach { case (a, i) => st.setString(i + 1, a) }
    val rs = st.executeQuery()
    try if (rs.next()) Some(rs.getString(1)) else None
    finally rs.close()
  }

  private def execute(st: PreparedStatement, args: String*): Unit = {
    args.zipWithIndex.foreach { case (a, i) => st.setString(i + 1, a) }
    st.executeUpdate()
    ()
  }
}
