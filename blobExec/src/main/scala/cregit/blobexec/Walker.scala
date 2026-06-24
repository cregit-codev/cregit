package cregit.blobexec

import org.eclipse.jgit.lib.Constants.{OBJ_BLOB, OBJ_COMMIT}
import org.eclipse.jgit.lib._
import org.eclipse.jgit.revwalk.{RevCommit, RevSort, RevTag, RevWalk}
import org.eclipse.jgit.treewalk.{CanonicalTreeParser, TreeWalk}

import java.util.concurrent.{Executors, TimeUnit}
import scala.collection.immutable.{Map => IMap}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.matching.Regex

final case class WalkStats(
    commitsProcessed: Int,
    commitsAlreadyMapped: Int,
    blobsRunThroughCommand: Int,
    blobsCacheHit: Int,
    refsProjected: Int,
    aborted: Boolean
)

/**
 * Drive the walk that rebuilds `src` history into `dst`, persisting mappings
 * to `mapping`. See plan file `replicated-popping-patterson.md` for the full
 * algorithm.
 *
 * Side effects are confined to:
 *   - jgit `ObjectInserter` / `RefDatabase` (writes to dst)
 *   - `Mapping` (writes to SQLite)
 *   - blob worker thread pool (runs the external command)
 *
 * Pure data (tree plans, blob misses, mapped-parent lists) is built with
 * immutable structures.
 */
final class Walker(
    src: Repository,
    dst: Repository,
    mapping: Mapping,
    fileMask: Regex,
    command: String,
    abortOnError: Boolean,
    parallelism: Int
) {
  import Walker._

  // -- entry point ---------------------------------------------------------

  def run(): WalkStats = {
    val revWalk = new RevWalk(src)
    revWalk.sort(RevSort.TOPO, true)
    revWalk.sort(RevSort.REVERSE, true)

    try {
      markUninteresting(revWalk)
      markStartRefs(revWalk)

      val (commitsProcessed, blobsRun, blobsHit, aborted) = walkCommits(revWalk)
      val refsCount =
        if (aborted) 0
        else projectRefs(revWalk)

      WalkStats(
        commitsProcessed       = commitsProcessed,
        commitsAlreadyMapped   = 0,  // we don't double-count: only walked ones are reported
        blobsRunThroughCommand = blobsRun,
        blobsCacheHit          = blobsHit,
        refsProjected          = refsCount,
        aborted                = aborted
      )
    } finally revWalk.close()
  }

  // -- ref preparation -----------------------------------------------------

  /** For every commit sha already in `commit_map` that still exists in src,
    * mark it uninteresting so the walk skips it and its ancestors. */
  private def markUninteresting(revWalk: RevWalk): Unit = {
    mapping.allCommitOrigShas.foreach { sha =>
      val id = ObjectId.fromString(sha)
      try {
        val rc = revWalk.parseCommit(id)
        revWalk.markUninteresting(rc)
      } catch {
        case _: org.eclipse.jgit.errors.MissingObjectException => ()
        case _: org.eclipse.jgit.errors.IncorrectObjectTypeException => ()
      }
    }
  }

  /** Add every commit reachable from a ref tip as a walk start point. */
  private def markStartRefs(revWalk: RevWalk): Unit = {
    src.getRefDatabase.getRefs.asScala.foreach { ref =>
      val id = Option(ref.getObjectId)
      id.foreach { oid =>
        try {
          val obj = revWalk.parseAny(oid)
          val commit = obj match {
            case t: RevTag    => peelToCommit(t, revWalk).orNull
            case c: RevCommit => c
            case _            => null
          }
          if (commit ne null) revWalk.markStart(commit)
        } catch {
          case _: org.eclipse.jgit.errors.MissingObjectException => ()
          case _: org.eclipse.jgit.errors.IncorrectObjectTypeException => ()
        }
      }
    }
  }

  // -- commit loop ---------------------------------------------------------

  /** Returns (commitsProcessed, blobsRun, blobsHit, aborted). */
  private def walkCommits(revWalk: RevWalk): (Int, Int, Int, Boolean) = {
    val pool = Executors.newFixedThreadPool(parallelism)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    val treeInserter   = dst.newObjectInserter()
    val commitInserter = dst.newObjectInserter()

    var aborted     = false       // local mutation across the walk loop is unavoidable
    var commits     = 0
    var blobsRun    = 0
    var blobsHit    = 0

    try {
      val iter = revWalk.iterator()
      while (iter.hasNext && !aborted) {
        val rc = iter.next()
        val origCommitSha = rc.getId.name

        // Build a pure plan for this commit's tree.
        val (plan, artifacts) = buildTreePlan(rc.getTree.getId, pathPrefix = "")

        // Run all matching-blob misses in parallel.
        val (resolved, abortFromBlob) = resolveMisses(artifacts.misses, pool)

        if (abortFromBlob) {
          aborted = true
        } else {
          // Assemble the new tree id (bottom-up).
          val newTreeId = assemble(plan, resolved, treeInserter)

          // Record the resolved blobs and the top-level tree mapping.
          val parents = rc.getParents.toVector.map { p =>
            val pn = p.getId.name
            mapping.getCommit(pn).getOrElse(
              throw new IllegalStateException(s"parent commit $pn missing from commit_map while processing $origCommitSha")
            )
          }.map(ObjectId.fromString)

          val newCommit = buildCommit(rc, parents, newTreeId, commitInserter)

          mapping.inTx {
            // Matching blobs the cmd resolved (Replace or Skip outcomes).
            resolved.foreach { case ((origSha, path), newId) =>
              mapping.putBlob(origSha, path, newId.name)
            }
            // Identity rows for non-matching blobs we walked through.
            // INSERT OR IGNORE keeps the original processed_at if a prior
            // commit already recorded the same (orig_blob, path).
            artifacts.unchangedBlobs.foreach { case (id, path) =>
              mapping.putBlob(id.name, path, id.name)
            }
            mapping.putTree(rc.getTree.getId.name, newTreeId.name)
            mapping.putCommit(origCommitSha, newCommit.name)
          }

          commits  += 1
          blobsRun += artifacts.misses.size
          blobsHit += artifacts.hitCount
        }
      }

      treeInserter.flush()
      commitInserter.flush()
    } finally {
      treeInserter.close()
      commitInserter.close()
      pool.shutdown()
      val _ = pool.awaitTermination(1, TimeUnit.MINUTES)
    }

    (commits, blobsRun, blobsHit, aborted)
  }

  // -- tree planning -------------------------------------------------------

  /** Walk `origTreeId` under `pathPrefix` (e.g. "src/lib/"), producing:
    *   - a `TreePlan` describing how to rebuild the tree
    *   - the matching-blob misses to resolve via the external command
    *   - the count of matching-blob cache hits encountered (for stats)
    *   - the non-matching blobs we saw, as `(orig_id, full_path)` pairs,
    *     so the walker can record identity rows in `blob_map`
    *
    * Caveat: when `tree_map` short-circuits, we don't re-walk to record
    * blob paths. If the same subtree id later appears under a different
    * parent path, blob_map will miss those (blob, alt_path) rows.
    */
  private def buildTreePlan(origTreeId: ObjectId, pathPrefix: String): (TreePlan, PlanArtifacts) = {
    mapping.getTree(origTreeId.name) match {
      case Some(newName) =>
        (TreeExisting(ObjectId.fromString(newName)), PlanArtifacts.empty)
      case None =>
        val reader = src.newObjectReader()
        try {
          val tw = new TreeWalk(reader)
          tw.addTree(new CanonicalTreeParser(null, reader, origTreeId))
          tw.setRecursive(false)

          val builder        = Vector.newBuilder[EntryPlan]
          val missesBuilder  = Vector.newBuilder[BlobMissTask]
          val unchangedBldr  = Vector.newBuilder[(ObjectId, String)]
          var hits = 0
          while (tw.next()) {
            val mode = tw.getFileMode(0)
            val name = tw.getNameString
            val id   = tw.getObjectId(0)
            val fullPath = pathPrefix + name

            if (mode == FileMode.TREE) {
              val (sub, subArtifacts) = buildTreePlan(id, fullPath + "/")
              builder += EntrySubtree(name, mode, sub)
              missesBuilder ++= subArtifacts.misses
              unchangedBldr ++= subArtifacts.unchangedBlobs
              hits += subArtifacts.hitCount
            } else if (mode == FileMode.GITLINK) {
              // Gitlinks reference commits in other repos; not stored in dst,
              // not recorded in blob_map (they aren't blobs).
              builder += EntryUnchanged(name, mode, id, copyBytes = false)
            } else {
              // Blob (regular file, executable, or symlink).
              if (fileMask.findFirstIn(name).isDefined) {
                mapping.getBlob(id.name, fullPath) match {
                  case Some(newSha) =>
                    builder += EntryBlobHit(name, mode, ObjectId.fromString(newSha))
                    hits += 1
                  case None =>
                    builder += EntryBlobMiss(name, mode, id, fullPath)
                    missesBuilder += BlobMissTask(id, name, fullPath)
                }
              } else {
                builder += EntryUnchanged(name, mode, id, copyBytes = true)
                unchangedBldr += ((id, fullPath))
              }
            }
          }
          val artifacts = PlanArtifacts(missesBuilder.result(), hits, unchangedBldr.result())
          (TreeBuild(builder.result()), artifacts)
        } finally reader.close()
    }
  }

  // -- parallel blob resolution -------------------------------------------

  /** Run each miss through the external command on the pool. Returns the
    * resolved map and an aborted flag. */
  private def resolveMisses(
      misses: Vector[BlobMissTask],
      pool: java.util.concurrent.ExecutorService
  )(implicit ec: ExecutionContext): (IMap[(String, String), ObjectId], Boolean) = {
    if (misses.isEmpty) return (IMap.empty, false)

    // De-dup so we don't run the command twice for the same key within a
    // single commit (e.g. the same (blob, path) reached via two subtrees).
    val unique = misses.map(m => (m.origId.name, m.fullPath) -> m).toMap.values.toVector

    val futures = unique.map { task =>
      Future {
        val bytes = readBlob(task.origId)
        val workerInserter = dst.newObjectInserter()
        try {
          val outcome = BlobExec.run(
            bytes        = bytes,
            origSha      = task.origId.name,
            filename     = task.filename,
            fullPath     = task.fullPath,
            command      = command,
            abortOnError = abortOnError,
            inserter     = workerInserter
          )
          // For Skip outcomes (identical output OR non-zero exit with
          // abortOnError=false) we keep the original blob id, so the dst
          // tree will reference it — meaning the bytes must exist in dst.
          // For Replace outcomes the worker has already inserted the new
          // blob. For Abort we do nothing (caller short-circuits).
          outcome match {
            case BlobExec.Outcome.Skip => workerInserter.insert(OBJ_BLOB, bytes); ()
            case _                     => ()
          }
          workerInserter.flush()
          (task, outcome)
        } finally workerInserter.close()
      }
    }

    val results = Await.result(Future.sequence(futures), Duration.Inf)

    val abort = results.exists { case (_, o) => o.isInstanceOf[BlobExec.Outcome.Abort] }
    if (abort) (IMap.empty, true)
    else {
      val resolved = results.iterator.collect {
        case (task, BlobExec.Outcome.Replace(newId)) =>
          (task.origId.name, task.fullPath) -> newId
        case (task, BlobExec.Outcome.Skip) =>
          // identical or non-zero-exit (with abortOnError=false): keep orig
          (task.origId.name, task.fullPath) -> task.origId
      }.toMap
      (resolved, false)
    }
  }

  private def readBlob(id: ObjectId): Array[Byte] = {
    val r = src.newObjectReader()
    try r.open(id, OBJ_BLOB).getBytes
    finally r.close()
  }

  // -- assembly ------------------------------------------------------------

  /** Walk the plan bottom-up and write trees into dst, returning the top id. */
  private def assemble(
      plan: TreePlan,
      resolved: IMap[(String, String), ObjectId],
      inserter: ObjectInserter
  ): ObjectId = plan match {
    case TreeExisting(id) => id
    case TreeBuild(entries) =>
      val tf = new org.eclipse.jgit.lib.TreeFormatter
      val resolvedEntries = entries.map(resolveEntry(_, resolved, inserter))
      // jgit requires sorted entries (git tree order). The TreeWalk visited
      // them in tree order already, so we keep that order.
      resolvedEntries.foreach { e =>
        tf.append(e.name, e.mode, e.id)
        if (e.copyBytes) copyBlobIfMissing(e.id, inserter)
      }
      inserter.insert(tf)
  }

  private def resolveEntry(
      entry: EntryPlan,
      resolved: IMap[(String, String), ObjectId],
      inserter: ObjectInserter
  ): ResolvedEntry = entry match {
    case EntryUnchanged(name, mode, id, copyBytes) =>
      ResolvedEntry(name, mode, id, copyBytes)
    case EntrySubtree(name, mode, sub) =>
      val subId = assemble(sub, resolved, inserter)
      ResolvedEntry(name, mode, subId, copyBytes = false)
    case EntryBlobHit(name, mode, newId) =>
      // The new blob was inserted in a prior run; verify-or-copy is unneeded
      // because dst is the only consumer and we always insert before mapping.
      ResolvedEntry(name, mode, newId, copyBytes = false)
    case EntryBlobMiss(name, mode, origId, fullPath) =>
      val newId = resolved((origId.name, fullPath))
      ResolvedEntry(name, mode, newId, copyBytes = false)
  }

  /** Copy a blob from src into dst if it isn't already present.
    * Cheaper to just re-insert (jgit dedupes by id) than to query first. */
  private def copyBlobIfMissing(id: ObjectId, inserter: ObjectInserter): Unit = {
    val reader = src.newObjectReader()
    try {
      val loader = reader.open(id, OBJ_BLOB)
      val bytes  = loader.getBytes
      inserter.insert(OBJ_BLOB, bytes)
      ()
    } finally reader.close()
  }

  // -- commit construction -------------------------------------------------

  private def buildCommit(
      orig: RevCommit,
      parents: Vector[ObjectId],
      newTree: ObjectId,
      inserter: ObjectInserter
  ): ObjectId = {
    val cb = new CommitBuilder
    cb.setAuthor(orig.getAuthorIdent)
    cb.setCommitter(orig.getCommitterIdent)
    val enc = try orig.getEncoding catch { case _: Throwable => java.nio.charset.StandardCharsets.UTF_8 }
    cb.setEncoding(enc)
    cb.setMessage(FormerCommitFooter.append(orig.getFullMessage, orig.getId.name))
    cb.setTreeId(newTree)
    cb.setParentIds(parents: _*)
    inserter.insert(cb)
  }

  // -- ref projection ------------------------------------------------------

  // Project every src ref into dst. Returns the number of refs written.
  // After projection, prunes any dst refs under refs/heads/ or refs/tags/
  // that no longer exist in src (mirror semantics), and removes corresponding
  // tag_map rows. Out-of-band refs (refs/notes/ etc) are left alone.
  private def projectRefs(revWalk: RevWalk): Int = {
    val tagInserter = dst.newObjectInserter()
    try {
      val refs = src.getRefDatabase.getRefs.asScala.toVector
      // One DB transaction covers every tag_map put/delete this projection
      // produces. Individual jgit ref updates are atomic at the git level
      // but not collectively; a re-run reconciles any partial ref state.
      mapping.inTx {
        val written = refs.foldLeft(0) { (acc, ref) =>
          if (projectOneRef(ref, revWalk, tagInserter)) acc + 1 else acc
        }
        tagInserter.flush()
        copyHeadSymref()
        pruneStaleRefs(refs.map(_.getName).toSet)
        written
      }
    } finally tagInserter.close()
  }

  // True if the ref was projected.
  // Records every non-symbolic ref under refs/heads/, refs/tags/, refs/remotes/
  // into ref_map; symbolic refs are linked but not recorded.
  private def projectOneRef(ref: Ref, revWalk: RevWalk, tagInserter: ObjectInserter): Boolean = {
    val name = ref.getName
    if (name == Constants.HEAD) return false  // handled separately
    if (ref.isSymbolic) {
      // Mirror symbolic refs as-is (target is another ref name).
      val target = ref.getTarget.getName
      val ru = dst.getRefDatabase.newUpdate(name, false)
      ru.link(target)
      return true
    }

    val obj = Option(ref.getObjectId).map(id => safelyParse(revWalk, id))
    obj match {
      case Some(Some(tag: RevTag)) =>
        val peeled = peelToCommit(tag, revWalk)
        peeled.flatMap(c => mapping.getCommit(c.getId.name).map(nc => (c.getId.name, nc))) match {
          case Some((origCommitSha, newCommitSha)) =>
            val newTagId = rewriteAnnotatedTag(tag, ObjectId.fromString(newCommitSha), tagInserter)
            writeRef(name, newTagId)
            if (isTrackedRef(name)) {
              mapping.putRef(Mapping.RefRow(
                refName    = name,
                kind       = "annotated_tag",
                origTarget = tag.getId.name,
                newTarget  = newTagId.name,
                origCommit = origCommitSha,
                newCommit  = newCommitSha
              ))
            }
            true
          case None => false
        }
      case Some(Some(commit: RevCommit)) =>
        mapping.getCommit(commit.getId.name) match {
          case Some(newSha) =>
            writeRef(name, ObjectId.fromString(newSha))
            if (isTrackedRef(name)) {
              val rowKind = if (isTagName(name)) "lightweight_tag" else "head"
              mapping.putRef(Mapping.RefRow(
                refName    = name,
                kind       = rowKind,
                origTarget = commit.getId.name,
                newTarget  = newSha,
                origCommit = commit.getId.name,
                newCommit  = newSha
              ))
            }
            true
          case None => false
        }
      case _ => false
    }
  }

  private def isTagName(refName: String): Boolean    = refName.startsWith("refs/tags/")
  private def isHeadName(refName: String): Boolean   = refName.startsWith("refs/heads/")
  private def isRemoteName(refName: String): Boolean = refName.startsWith("refs/remotes/")
  // Namespaces we both record in ref_map and prune for mirror semantics.
  private def isTrackedRef(refName: String): Boolean =
    isHeadName(refName) || isTagName(refName) || isRemoteName(refName)

  // Delete dst refs in tracked namespaces that aren't in `seen`, and remove
  // their ref_map rows. Out-of-band refs (e.g. refs/notes/*) are left alone.
  private def pruneStaleRefs(seenSrcRefNames: Set[String]): Unit = {
    val dstRefs = dst.getRefDatabase.getRefs.asScala.toVector
    val stale = dstRefs.filter { r =>
      val n = r.getName
      isTrackedRef(n) && !seenSrcRefNames.contains(n)
    }
    stale.foreach { r =>
      val n = r.getName
      val ru = dst.getRefDatabase.newUpdate(n, true)
      ru.setForceUpdate(true)
      ru.delete()
      mapping.deleteRef(n)
    }
  }

  private def safelyParse(revWalk: RevWalk, id: ObjectId): Option[org.eclipse.jgit.revwalk.RevObject] =
    try Some(revWalk.parseAny(id))
    catch {
      case _: org.eclipse.jgit.errors.MissingObjectException => None
      case _: Throwable => None
    }

  private def peelToCommit(tag: RevTag, revWalk: RevWalk): Option[RevCommit] = {
    var current: org.eclipse.jgit.revwalk.RevObject = tag
    while (current.isInstanceOf[RevTag]) {
      val inner = current.asInstanceOf[RevTag].getObject
      current = safelyParse(revWalk, inner).orNull
      if (current eq null) return None
    }
    current match {
      case c: RevCommit => Some(c)
      case _            => None
    }
  }

  private def rewriteAnnotatedTag(orig: RevTag, mappedCommit: ObjectId, inserter: ObjectInserter): ObjectId = {
    val tb = new TagBuilder
    tb.setTag(orig.getTagName)
    tb.setTagger(orig.getTaggerIdent)
    tb.setMessage(orig.getFullMessage)
    tb.setObjectId(mappedCommit, OBJ_COMMIT)
    inserter.insert(tb)
  }

  private def writeRef(name: String, newId: ObjectId): Unit = {
    val ru = dst.getRefDatabase.newUpdate(name, true)
    ru.setNewObjectId(newId)
    ru.setForceUpdate(true)
    ru.update()
    ()
  }

  private def copyHeadSymref(): Unit = {
    val srcHead = src.exactRef(Constants.HEAD)
    if ((srcHead ne null) && srcHead.isSymbolic) {
      val target = srcHead.getTarget.getName
      val ru = dst.getRefDatabase.newUpdate(Constants.HEAD, false)
      ru.link(target)
      ()
    }
  }
}

object Walker {

  // Pure data describing how to assemble a rewritten tree. Hoisted out of
  // `Walker` so case-class pattern matches don't need to check an outer
  // reference (scalac warning 'outer reference in this type test...').

  sealed trait TreePlan
  final case class TreeExisting(newId: ObjectId) extends TreePlan
  final case class TreeBuild(entries: Vector[EntryPlan]) extends TreePlan

  sealed trait EntryPlan { def name: String; def mode: FileMode }
  /** Verbatim pass-through: non-matching blob, gitlink, etc. The bytes are
    * copied into dst when `copyBytes` is true (regular blobs) and skipped
    * otherwise (gitlinks reference commits in other repos). */
  final case class EntryUnchanged(name: String, mode: FileMode, id: ObjectId, copyBytes: Boolean) extends EntryPlan
  final case class EntrySubtree(name: String, mode: FileMode, plan: TreePlan) extends EntryPlan
  final case class EntryBlobHit(name: String, mode: FileMode, newId: ObjectId) extends EntryPlan
  /** Matching blob whose `(origSha, fullPath)` is not yet in `blob_map`. */
  final case class EntryBlobMiss(name: String, mode: FileMode, origId: ObjectId, fullPath: String) extends EntryPlan

  /** A matching blob that needs to go through the external command. We
    * carry both the basename (`filename`, exposed as `BFG_FILENAME` for
    * backward compatibility with `tokenBySha.pl`) and the full repo-root
    * relative path (`fullPath`, exposed as `BFG_PATH` and used as the
    * `blob_map` cache key). */
  final case class BlobMissTask(origId: ObjectId, filename: String, fullPath: String)

  final case class ResolvedEntry(name: String, mode: FileMode, id: ObjectId, copyBytes: Boolean)

  /** What a `buildTreePlan` call discovered alongside the plan itself. */
  final case class PlanArtifacts(
      misses: Vector[BlobMissTask],
      hitCount: Int,
      unchangedBlobs: Vector[(ObjectId, String)]  // (orig_id, full_path)
  )

  object PlanArtifacts {
    val empty: PlanArtifacts = PlanArtifacts(Vector.empty, 0, Vector.empty)
  }
}
