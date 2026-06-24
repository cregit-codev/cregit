package cregit.blobexec

import java.nio.file.Path
import java.sql.{Connection, DriverManager, PreparedStatement}

/**
 * SQLite-backed persistent mapping between original and rewritten object ids.
 *
 * The walker is single-threaded for DB writes (only the external command runs
 * in parallel, and worker threads return their results to the walker which
 * then persists them), so we hold one JDBC connection and rely on it being
 * confined to the walker thread.
 *
 * Each row carries a `processed_at` column (seconds since the unix epoch),
 * stamped by SQLite at insertion time. Per-row timestamps let callers
 * identify all rows produced by a given run (`WHERE processed_at >= T`).
 *
 * For non-matching blobs the walker records an identity row
 * (`orig_blob == new_blob`); this keeps the table as the authoritative
 * "blobs we've seen in src" set, useful for verification and audit.
 *
 * Mutation is unavoidable here — JDBC is intrinsically imperative. Callers
 * see a pure interface (`get*` returns `Option`, `put*` and `setMeta` return
 * `Unit` and are invoked inside `inTx`); internal state is just the cached
 * `PreparedStatement` handles.
 */
final class Mapping private (conn: Connection) extends AutoCloseable {

  // INSERT OR IGNORE so processed_at reflects first-write time, not last.
  // The data values are deterministic for a given (key, command, mask), so
  // silently keeping the original row on conflict is safe.
  private val selBlob   = conn.prepareStatement("SELECT new_blob FROM blob_map WHERE orig_blob = ? AND path = ?")
  private val insBlob   = conn.prepareStatement("INSERT OR IGNORE INTO blob_map(orig_blob, path, new_blob) VALUES (?, ?, ?)")
  private val selCommit = conn.prepareStatement("SELECT new_commit FROM commit_map WHERE orig_commit = ?")
  private val insCommit = conn.prepareStatement("INSERT OR IGNORE INTO commit_map(orig_commit, new_commit) VALUES (?, ?)")
  private val selTree   = conn.prepareStatement("SELECT new_tree FROM tree_map WHERE orig_tree = ?")
  private val insTree   = conn.prepareStatement("INSERT OR IGNORE INTO tree_map(orig_tree, new_tree) VALUES (?, ?)")
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

  def putBlob(origBlob: String, path: String, newBlob: String): Unit =
    Mapping.execute(insBlob, origBlob, path, newBlob)

  def getCommit(origCommit: String): Option[String] =
    Mapping.selectString(selCommit, origCommit)

  def putCommit(origCommit: String, newCommit: String): Unit =
    Mapping.execute(insCommit, origCommit, newCommit)

  def getTree(origTree: String): Option[String] =
    Mapping.selectString(selTree, origTree)

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
    List(selBlob, insBlob, selCommit, insCommit, selTree, insTree,
         selRef, insRef, delRef, selMeta, insMeta)
      .foreach(s => try s.close() catch { case _: Throwable => () })
    conn.close()
  }
}

object Mapping {

  /** Mismatch between the recorded command/mask and the values passed in. */
  final class MetaMismatchException(message: String) extends RuntimeException(message)

  // strftime('%s','now') yields unix epoch seconds as a string; we store it
  // as INTEGER so range filters / arithmetic stay numeric.
  //
  // Foreign keys: ref_map.orig_commit must exist in commit_map(orig_commit).
  // ON DELETE CASCADE so cleaning up a commit's mapping also removes any
  // refs that pointed at it. There is no natural FK from blob_map or
  // tree_map back into commit_map (the same blob/tree appears in many
  // commits; we don't track which one introduced it).
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

  // A row in ref_map. Covers every kind of ref the walker projects:
  //   - kind = "head"            -- refs/heads/ and refs/remotes/;
  //                                 orig_target == orig_commit, new_target == new_commit
  //   - kind = "annotated_tag"   -- refs/tags/<name> backed by a tag object;
  //                                 orig/new target are the tag-object SHAs, commit fields are peeled
  //   - kind = "lightweight_tag" -- refs/tags/<name> pointing directly at a commit;
  //                                 orig_target == orig_commit, new_target == new_commit (like head)
  final case class RefRow(
      refName: String,
      kind: String,
      origTarget: String,
      newTarget: String,
      origCommit: String,
      newCommit: String
  )

  /** Enable SQLite FK enforcement on a fresh connection. Required per-connection. */
  private def enableForeignKeys(conn: Connection): Unit = {
    val st = conn.createStatement()
    try st.execute("PRAGMA foreign_keys = ON") finally st.close()
  }

  /**
   * Open or create the SQLite database at `path`. Creates the schema if
   * absent. Validates `command` and `mask` against the `meta` table: on
   * mismatch, throws `MetaMismatchException`; on absence, records them.
   */
  def open(path: Path, command: String, mask: String): Mapping = {
    val url = s"jdbc:sqlite:${path.toAbsolutePath}"
    val conn = DriverManager.getConnection(url)
    enableForeignKeys(conn)
    val st = conn.createStatement()
    try Schema.foreach(st.execute) finally st.close()

    val m = new Mapping(conn)
    checkOrSetMeta(m, "command", command)
    checkOrSetMeta(m, "mask",    mask)
    m
  }

  /** In-memory variant used by tests. */
  def openInMemory(command: String, mask: String): Mapping = {
    val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
    enableForeignKeys(conn)
    val st = conn.createStatement()
    try Schema.foreach(st.execute) finally st.close()
    val m = new Mapping(conn)
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
