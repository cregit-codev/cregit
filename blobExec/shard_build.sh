#!/usr/bin/env bash
# Sharded whole-history tokenize for cregit blobExec (any repo).
# Runs N tree-only shards in parallel (blobExec --shard=k/N), then merges +
# serial re-folds via shard_merge.py into <out>/final/{dst.git,blobmap.db},
# byte-identical to a single serial run. Front-half companion to shard_merge.py.
#
# Must run inside `devenv shell` (java/srcml/ctags/python3 on PATH). Example:
#   devenv shell -- blobExec/shard_build.sh --src repo.git --out /tmp/build --shards 4
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # blobExec/
CREGIT="$(cd "$HERE/.." && pwd)"                        # repo root

SRC=""; OUT=""; N=4; THREADS=""
JAR="$HERE/target/scala-2.13/blobExec-0.1.0-assembly.jar"
COMMAND="$CREGIT/tokenizeByBlobId/tokenBySha.pl"
MASK='\.[ch]$'
TOK_CMD=""; WARM_DB=""; WARM_GIT=""

usage() {
  cat >&2 <<EOF
usage: shard_build.sh --src <bare.git> --out <dir> [options]
  --src DIR        bare source repo (read-only)                 [required]
  --out DIR        output dir (per-shard dirs + final/ go here) [required]
  --shards N       number of parallel tree-only shards          [default: $N]
  --threads T      JVM ActiveProcessorCount per shard           [default: nproc/N]
  --jar PATH       blobExec assembly jar                        [default: target/…assembly.jar]
  --command PATH   per-blob tokenizer script                    [default: tokenizeByBlobId/tokenBySha.pl]
  --mask REGEX     file mask                                     [default: $MASK]
  --tok-cmd CMD    BFG_TOKENIZE_CMD                              [default: tokenize/tokenize.pl …]
  --warm-db DB     frozen prior blobmap.db (incremental)        [optional]
  --warm-git GIT   frozen prior dst.git to union objects from   [optional]
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --src) SRC="$2"; shift 2;;
    --out) OUT="$2"; shift 2;;
    --shards) N="$2"; shift 2;;
    --threads) THREADS="$2"; shift 2;;
    --jar) JAR="$2"; shift 2;;
    --command) COMMAND="$2"; shift 2;;
    --mask) MASK="$2"; shift 2;;
    --tok-cmd) TOK_CMD="$2"; shift 2;;
    --warm-db) WARM_DB="$2"; shift 2;;
    --warm-git) WARM_GIT="$2"; shift 2;;
    -h|--help) usage; exit 0;;
    *) echo "unknown arg: $1" >&2; usage; exit 2;;
  esac
done

[ -n "$SRC" ] && [ -n "$OUT" ] || { usage; exit 2; }
[ -d "$SRC" ]  || { echo "FATAL: src repo not found: $SRC" >&2; exit 1; }
[ "$N" -ge 1 ] 2>/dev/null || { echo "FATAL: --shards must be a positive integer" >&2; exit 2; }
[ -f "$JAR" ]  || { echo "FATAL: jar not found (build it: cd blobExec && sbt assembly): $JAR" >&2; exit 1; }
command -v java >/dev/null    || { echo "FATAL: java not on PATH (run inside devenv shell)" >&2; exit 1; }
command -v python3 >/dev/null || { echo "FATAL: python3 not on PATH (run inside devenv shell)" >&2; exit 1; }

# Default threads: spread cores across the N concurrent shards.
if [ -z "$THREADS" ]; then
  NPROC="$(nproc 2>/dev/null || echo 4)"
  THREADS=$(( NPROC / N )); [ "$THREADS" -ge 1 ] || THREADS=1
fi

# Default tokenizer dispatcher (srcML for .c/.h, rustTokenizer for .rs, …); the
# --srcml/--ctags paths are forwarded only to the srcML parser.
if [ -z "$TOK_CMD" ]; then
  TOK_CMD="$CREGIT/tokenize/tokenize.pl --srcml2token=$CREGIT/tokenize/srcMLtoken/srcml2token --srcml=$(command -v srcml) --ctags=$(command -v ctags)"
fi
export BFG_TOKENIZE_CMD="$TOK_CMD"
JAVA="$(command -v java)"

mkdir -p "$OUT"
LOG="$OUT/shard_build.log"
log() { echo "[$(date -u '+%F %T')] $*" | tee -a "$LOG"; }

# Single-instance guard so a re-launch (incremental resume) never double-runs.
exec 9>"$OUT/shard_build.lock"
flock -n 9 || { log "another shard build holds the lock -- exiting"; exit 0; }

log "=== sharded build: N=$N threads/shard=$THREADS src=$SRC out=$OUT warm=${WARM_DB:-none} ==="

run_shard() {
  local k="$1" sd="$OUT/shard-$k" memo="$OUT/memo-$k" rc t0 t1
  local warm=()
  mkdir -p "$sd" "$memo"
  [ -n "$WARM_DB" ] && warm=(--warm="$WARM_DB")
  t0=$(date +%s)
  BFG_MEMO_DIR="$memo" "$JAVA" -XX:ActiveProcessorCount="$THREADS" -XX:+ExitOnOutOfMemoryError \
    -jar "$JAR" "--shard=$k/$N" "${warm[@]}" \
    "$SRC" "$sd/dst.git" "$sd/blobmap.db" "$COMMAND" "$MASK" \
    > "$sd/run.log" 2>&1
  rc=$?; t1=$(date +%s)
  log "shard $k exit=$rc wall=$((t1-t0))s :: $(tail -n1 "$sd/run.log" 2>/dev/null)"
  return $rc
}

log "launching $N shards (ActiveProcessorCount=$THREADS each)"
G0=$(date +%s); pids=(); ks=()
for k in $(seq 0 $((N-1))); do run_shard "$k" & pids+=($!); ks+=("$k"); done
fail=0
for i in "${!pids[@]}"; do
  wait "${pids[$i]}" || { log "SHARD ${ks[$i]} FAILED (see $OUT/shard-${ks[$i]}/run.log)"; fail=1; }
done
log "shard phase wall=$(( $(date +%s) - G0 ))s fail=$fail"
[ $fail -eq 0 ] || { log "ABORT: a shard failed"; exit 1; }

FINAL="$OUT/final"
MERGE=(--src "$SRC" --final "$FINAL" --jar "$JAR" --command "$COMMAND" --mask "$MASK" \
  --tok-cmd "$BFG_TOKENIZE_CMD" --memo "$OUT/memo-refold")
for k in $(seq 0 $((N-1))); do MERGE+=(--shard "$OUT/shard-$k"); done
[ -n "$WARM_DB" ]  && MERGE+=(--warm-db "$WARM_DB")
[ -n "$WARM_GIT" ] && MERGE+=(--warm-git "$WARM_GIT")

log "merge + serial re-fold"
python3 "$HERE/shard_merge.py" "${MERGE[@]}" 2>&1 | tee -a "$LOG"
mrc=${PIPESTATUS[0]}
[ "$mrc" -eq 0 ] || { log "ABORT: merge failed (exit $mrc)"; exit 1; }

log "=== DONE. final DB: $FINAL/blobmap.db  final git: $FINAL/dst.git ==="
