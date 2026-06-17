set -euo pipefail

die() {
    log "ERROR: $1"
    exit 1
}

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

FROM_STEP=${1:-1}

step() {
    STEP_NUM=${STEP_NUM:-0}
    STEP_NUM=$((STEP_NUM + 1))
    STEP_START=$(date +%s)
    [ "$STEP_NUM" -lt "$FROM_STEP" ] && return 0
    echo ""
    echo "═══════════════════════════════════════════════════════════════════"
    echo "  Step $STEP_NUM — $1"
    echo "═══════════════════════════════════════════════════════════════════"
}

end_step() {
    [ "$STEP_NUM" -lt "$FROM_STEP" ] && return 0
    local elapsed=$(( $(date +%s) - STEP_START ))
    echo "  ✓ completed in ${elapsed}s"
}

CREGIT=$(pwd)
BFG="${CREGIT}/blobExec/target/scala-2.13/blobExec-0.1.0-assembly.jar"
WORK="../cregit-files"
REPO_GIT_URL="https://github.com/jqlang/jq.git"
REPO_COMMIT_URL="https://github.com/jqlang/jq/commit/"
REPO_NAME="jq"

REPO_PATH_ORIGINAL="${WORK}/${REPO_NAME}-original"
REPO_PATH_CREGIT="${WORK}/${REPO_NAME}-cregit"
REPO_PATH_ORIGINAL_BARE="${REPO_PATH_ORIGINAL}.git"
REPO_PATH_CREGIT_BARE="${REPO_PATH_CREGIT}.git"

DB_PATH_ORIGINAL="${REPO_PATH_ORIGINAL}.db"
DB_PATH_CREGIT="${REPO_PATH_CREGIT}.db"
DB_PATH_PERSONS="${WORK}/${REPO_NAME}-persons.db"
XLS_PATH_PERSONS="${WORK}/${REPO_NAME}-persons.xls"
DATASET_PATH="${WORK}/${REPO_NAME}-dataset.parquet"

PYTHON=$(which python3)

cleanup() {
    local ec=$?
    if [ $ec -ne 0 ] && [ "$FROM_STEP" = "1" ] && [ -n "$WORK" ] && [ "$WORK" != "/" ]; then
        log "Pipeline failed (exit $ec) — removing $WORK for a clean restart"
        rm -rf "$WORK"
    fi
}
trap cleanup EXIT

# A full run starts clean; resuming (FROM_STEP >= 2) keeps existing work.
if [ "$FROM_STEP" = "1" ] && [ -d "$WORK" ] && [ -n "$WORK" ] && [ "$WORK" != "/" ]; then
    rm -rf "$WORK"
fi

LOG_FILE="${WORK}/pipeline.log"
mkdir -p $WORK/memo $WORK/blame $WORK/html
exec > >(tee -a "$LOG_FILE") 2>&1

echo ""
echo "████████████████████████████████████████████████████████████████████████"
echo "  CreGit Pipeline — jq"
echo "  Log: $LOG_FILE"
echo "████████████████████████████████████████████████████████████████████████"
echo ""

# ---------------------------------------------------------------------------
# Step 1 — clone bare original repo
# ---------------------------------------------------------------------------
step "clone bare original repo"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
git clone --bare $REPO_GIT_URL $REPO_PATH_ORIGINAL_BARE
fi
end_step

# ---------------------------------------------------------------------------
# Step 2 — clone bare copy for cregit usage (cregit-view repo)
# ---------------------------------------------------------------------------
step "clone bare copy for cregit usage"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -d "$REPO_PATH_ORIGINAL_BARE" ] || die "step 1 did not produce $REPO_PATH_ORIGINAL_BARE"
git clone --bare $REPO_PATH_ORIGINAL_BARE $REPO_PATH_CREGIT_BARE
fi
end_step

# ---------------------------------------------------------------------------
# Step 3 — BFG tokenize (replace .c/.h blobs with token-level representation)
# ---------------------------------------------------------------------------
step "BFG tokenize"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -d "$REPO_PATH_CREGIT_BARE" ] || die "step 2 did not produce $REPO_PATH_CREGIT_BARE"

export BFG_MEMO_DIR="${WORK}/memo"

export BFG_TOKENIZE_CMD="${CREGIT}/tokenize/tokenizeSrcMl.pl \
  --srcml2token=${CREGIT}/tokenize/srcMLtoken/srcml2token \
  --srcml=$(which srcml) \
  --ctags=$(which ctags)"

java -jar $BFG \
  $REPO_PATH_CREGIT_BARE \
  ${CREGIT}/tokenizeByBlobId/tokenBySha.pl \
  '\.[ch]$'

git --git-dir=$REPO_PATH_CREGIT_BARE reflog expire --expire=now --all
git --git-dir=$REPO_PATH_CREGIT_BARE gc --prune=now --aggressive
fi
end_step

# ---------------------------------------------------------------------------
# Step 4 — git log DB (original filtered repo)
# ---------------------------------------------------------------------------
step "git log DB (original repo)"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -d "$REPO_PATH_CREGIT_BARE" ] || die "step 3 did not complete"
java -jar $CREGIT/slickGitLog/target/scala-2.10/slickgitlog_2.10-0.1-SNAPSHOT-one-jar.jar \
  $DB_PATH_ORIGINAL $REPO_PATH_ORIGINAL_BARE
fi
end_step

# ---------------------------------------------------------------------------
# Step 5 — git log DB (cregit repo)
# ---------------------------------------------------------------------------
step "git log DB (cregit repo)"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -f "$DB_PATH_ORIGINAL" ] || die "step 4 did not produce $DB_PATH_ORIGINAL"
java -jar $CREGIT/slickGitLog/target/scala-2.10/slickgitlog_2.10-0.1-SNAPSHOT-one-jar.jar \
  $DB_PATH_CREGIT $REPO_PATH_CREGIT_BARE
fi
end_step

# ---------------------------------------------------------------------------
# Step 6 — persons DB
# ---------------------------------------------------------------------------
step "persons DB"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -f "$DB_PATH_CREGIT" ] || die "step 5 did not produce $DB_PATH_CREGIT"
java -jar $CREGIT/persons/target/scala-2.10/persons_2.10-0.1-SNAPSHOT-one-jar.jar \
  $REPO_PATH_ORIGINAL_BARE $XLS_PATH_PERSONS $DB_PATH_PERSONS
fi
end_step

# ---------------------------------------------------------------------------
# Step 7 — clone non-bare working clones (for blame / HTML gen)
# ---------------------------------------------------------------------------
step "clone non-bare working clones"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -f "$DB_PATH_PERSONS" ] || die "step 6 did not produce $DB_PATH_PERSONS"
git clone $REPO_PATH_ORIGINAL_BARE $REPO_PATH_ORIGINAL
git clone $REPO_PATH_CREGIT_BARE $REPO_PATH_CREGIT
fi
end_step

# ---------------------------------------------------------------------------
# Step 8 — blame
# ---------------------------------------------------------------------------
step "blame"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -d "$REPO_PATH_CREGIT" ] || die "step 7 did not produce $REPO_PATH_CREGIT"
perl $CREGIT/blameRepo/blameRepoFiles.pl --verbose \
  --formatBlame=$CREGIT/blameRepo/formatBlame.pl \
  $REPO_PATH_CREGIT $WORK/blame '\.[ch]$'
fi
end_step

# ---------------------------------------------------------------------------
# Step 9 — remap commits (cregit → original commit mapping)
# ---------------------------------------------------------------------------
step "remap commits"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -d "$WORK/blame" ] || die "step 8 did not run (blame dir missing)"
java -jar $CREGIT/remapCommits/target/scala-2.10/remapcommits_2.10-0.1-SNAPSHOT-one-jar.jar \
  $DB_PATH_CREGIT $REPO_PATH_CREGIT_BARE
fi
end_step

# ---------------------------------------------------------------------------
# Step 10 — generate HTML views
# ---------------------------------------------------------------------------
step "generate HTML views"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -f "$DB_PATH_CREGIT" ] || die "step 9 did not complete"
perl $CREGIT/prettyPrint/prettyPrintFiles.pl --verbose \
  $DB_PATH_CREGIT $DB_PATH_PERSONS \
  $REPO_PATH_ORIGINAL $WORK/blame $WORK/html \
  $REPO_COMMIT_URL '\.[ch]$'
fi
end_step

# ---------------------------------------------------------------------------
# Step 11 — generate unified Parquet dataset
# ---------------------------------------------------------------------------
step "generate Parquet dataset"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -f "$DB_PATH_CREGIT" ] || die "step 10 did not produce $DB_PATH_CREGIT"
$PYTHON $CREGIT/generate_dataset.py \
  --blame-dir  "$WORK/blame" \
  --source-dir "$REPO_PATH_ORIGINAL" \
  --cregit-db  "$DB_PATH_CREGIT" \
  --persons-db "$DB_PATH_PERSONS" \
  --output     "$DATASET_PATH" \
  --repo-name  "$REPO_NAME" \
  --verbose
fi
end_step
