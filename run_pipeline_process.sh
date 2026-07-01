#!/usr/bin/env bash
set -euo pipefail

die() {
  log "ERROR: $1"
  exit 1
}

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

usage() {
  cat <<'USAGE'
Usage: run_pipeline_process.sh [options] [from-step]

Run the full CreGit pipeline on a git repository.

Options:
  --repo-url <url>      Git repository URL (required for fresh runs).
                        Examples:
                          https://github.com/owner/repo.git
                          git@github.com:owner/repo.git
                          owner/repo  (assumes GitHub)

  --repo-name <name>    Short name for output files/directories.
                        Default: derived from --repo-url (e.g. "jq").

  --commit-url <url>    Commit URL template for HTML links.
                        Default: derived from --repo-url
                        (e.g. "https://github.com/owner/repo/commit/").

  --work-dir <path>     Output directory for all pipeline artifacts.
                        Default: ../cregit-files

  --file-filter <regex> File extension filter (Perl regex) for C/C++ files.
                        Default: '\.[ch]$'

  --bfg-jar <path>      Path to the BFG tokenizer JAR.
                        Default: blobExec/target/scala-2.13/blobExec-0.1.0-assembly.jar

  --keep-on-failure     Keep work directory on pipeline failure (for debugging).
                        Default: clean up on failure for fresh runs.

  --help                Show this message and exit.

Positional:
  from-step             Start from pipeline step N (default: 1).
                        Useful for resuming after a partial run.

Examples:
  # Full run (defaults to the jq repo)
  ./run_pipeline_process.sh

  # Run on a different repo
  ./run_pipeline_process.sh --repo-url https://github.com/stedolan/jq.git

  # Short form (assumes GitHub)
  ./run_pipeline_process.sh --repo-url torvalds/linux --repo-name linux

  # Resume from step 8 with custom work dir
  ./run_pipeline_process.sh --work-dir ../my-output 8

  # Keep artifacts on failure for debugging
  ./run_pipeline_process.sh --keep-on-failure
USAGE
}

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
CREGIT=$(pwd)
WORK="../cregit-files"
FROM_STEP=1
FILE_FILTER='\.[ch]$'
KEEP_ON_FAILURE=0
BFG_JAR="${CREGIT}/blobExec/target/scala-2.13/blobExec-0.1.0-assembly.jar"
REPO_GIT_URL=""
REPO_NAME=""
REPO_COMMIT_URL=""

# JAR paths (fixed, project-internal)
SLICKGITLOG_JAR="${CREGIT}/slickGitLog/target/scala-2.10/slickgitlog_2.10-0.1-SNAPSHOT-one-jar.jar"
PERSONS_JAR="${CREGIT}/persons/target/scala-2.10/persons_2.10-0.1-SNAPSHOT-one-jar.jar"
REMAPCOMMITS_JAR="${CREGIT}/remapCommits/target/scala-2.10/remapcommits_2.10-0.1-SNAPSHOT-one-jar.jar"

# ---------------------------------------------------------------------------
# CLI argument parsing
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
  case "$1" in
  --repo-url)
    [ $# -lt 2 ] && die "--repo-url requires an argument"
    REPO_GIT_URL="$2"
    shift 2
    ;;
  --repo-name)
    [ $# -lt 2 ] && die "--repo-name requires an argument"
    REPO_NAME="$2"
    shift 2
    ;;
  --commit-url)
    [ $# -lt 2 ] && die "--commit-url requires an argument"
    REPO_COMMIT_URL="$2"
    shift 2
    ;;
  --work-dir)
    [ $# -lt 2 ] && die "--work-dir requires an argument"
    WORK="$2"
    shift 2
    ;;
  --file-filter)
    [ $# -lt 2 ] && die "--file-filter requires an argument"
    FILE_FILTER="$2"
    shift 2
    ;;
  --bfg-jar)
    [ $# -lt 2 ] && die "--bfg-jar requires an argument"
    BFG_JAR="$2"
    shift 2
    ;;
  --keep-on-failure)
    KEEP_ON_FAILURE=1
    shift
    ;;
  --help)
    usage
    exit 0
    ;;
  --*)
    die "Unknown option: $1. Use --help for usage."
    ;;
  *)
    FROM_STEP="$1"
    shift
    ;;
  esac
done

# ---------------------------------------------------------------------------
# Derive missing values
# ---------------------------------------------------------------------------
if [ -z "$REPO_GIT_URL" ]; then
  # Default repo (backwards compatibility)
  REPO_GIT_URL="https://github.com/jqlang/jq.git"
fi

# Normalise URL: if no dots or slashes, treat as "owner/repo" on GitHub
if [[ "$REPO_GIT_URL" != */* ]]; then
  die "Invalid repo URL: '$REPO_GIT_URL'. Use format: owner/repo or full git URL."
fi
if [[ "$REPO_GIT_URL" != *//* ]] && [[ "$REPO_GIT_URL" != *:* ]]; then
  # Short form "owner/repo" → expand to GitHub URL
  REPO_GIT_URL="https://github.com/${REPO_GIT_URL}.git"
fi

# Derive repo name from URL: strip .git, take last path component
strip_git_suffix() {
  local u="$1"
  u="${u%.git}"
  echo "$u"
}

derive_repo_name() {
  local u
  u=$(strip_git_suffix "$1")
  basename "$u"
}

derive_commit_url() {
  local u
  u=$(strip_git_suffix "$1")
  echo "${u}/commit/"
}

if [ -z "$REPO_NAME" ]; then
  REPO_NAME=$(derive_repo_name "$REPO_GIT_URL")
fi

if [ -z "$REPO_COMMIT_URL" ]; then
  REPO_COMMIT_URL=$(derive_commit_url "$REPO_GIT_URL")
fi

# ---------------------------------------------------------------------------
# Derived paths
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# Cleanup trap
# ---------------------------------------------------------------------------
cleanup() {
  local ec=$?
  if [ $ec -ne 0 ]; then
    if [ "$KEEP_ON_FAILURE" = "1" ]; then
      log "Pipeline failed (exit $ec) — keeping $WORK for debugging"
    elif [ "$FROM_STEP" = "1" ] && [ -n "$WORK" ] && [ "$WORK" != "/" ]; then
      log "Pipeline failed (exit $ec) — removing $WORK for a clean restart"
      rm -rf "$WORK"
    fi
  fi
}
trap cleanup EXIT

# A full run starts clean; resuming (FROM_STEP >= 2) keeps existing work.
if [ "$FROM_STEP" = "1" ] && [ -d "$WORK" ] && [ -n "$WORK" ] && [ "$WORK" != "/" ]; then
  rm -rf "$WORK"
fi

# ---------------------------------------------------------------------------
# Pipeline helpers
# ---------------------------------------------------------------------------
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
  local elapsed=$(($(date +%s) - STEP_START))
  echo "  ✓ completed in ${elapsed}s"
}

# ---------------------------------------------------------------------------
# Pipeline header
# ---------------------------------------------------------------------------
LOG_FILE="${WORK}/pipeline.log"
mkdir -p $WORK/memo $WORK/blame $WORK/html
exec > >(tee -a "$LOG_FILE") 2>&1

echo ""
echo "████████████████████████████████████████████████████████████████████████"
echo "  CreGit Pipeline — ${REPO_NAME}"
echo "  Repo: $REPO_GIT_URL"
echo "  Work: $WORK"
echo "  Log:  $LOG_FILE"
echo "████████████████████████████████████████████████████████████████████████"
echo ""

# ---------------------------------------------------------------------------
# Step 1 — clone bare original repo
# ---------------------------------------------------------------------------
step "clone bare original repo"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
  # git clone --bare $REPO_GIT_URL $REPO_PATH_ORIGINAL_BARE
  #RESTORE LATER - ONLY FOR IIO CLONING ONLY DRIVERS

  git clone --filter=blob:none --no-checkout "$REPO_GIT_URL" "${WORK}/temp-iio"
  cd "${WORK}/temp-iio"
  git sparse-checkout init --cone
  git sparse-checkout set drivers
  git checkout master
  cd ..
  git clone --bare "${WORK}/temp-iio" $REPO_PATH_ORIGINAL_BARE

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

  java -Xms1g -Xmx4g -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -jar $BFG_JAR \
    $REPO_PATH_CREGIT_BARE \
    ${CREGIT}/tokenizeByBlobId/tokenBySha.pl \
    "$FILE_FILTER"

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
  java -Xms1g -Xmx4g -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -jar $SLICKGITLOG_JAR \
    $DB_PATH_ORIGINAL $REPO_PATH_ORIGINAL_BARE
fi
end_step

# ---------------------------------------------------------------------------
# Step 5 — git log DB (cregit repo)
# ---------------------------------------------------------------------------
step "git log DB (cregit repo)"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
  [ -f "$DB_PATH_ORIGINAL" ] || die "step 4 did not produce $DB_PATH_ORIGINAL"
  java -Xms1g -Xmx4g -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -jar -jar $SLICKGITLOG_JAR \
    $DB_PATH_CREGIT $REPO_PATH_CREGIT_BARE
fi
end_step

# ---------------------------------------------------------------------------
# Step 5b — fix missing footers in cregit DB (BFG blank-line issue)
# ---------------------------------------------------------------------------
step "fix missing footers in cregit DB"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
  [ -f "$DB_PATH_CREGIT" ] || die "step 5 did not produce $DB_PATH_CREGIT"
  $PYTHON $CREGIT/scripts/fix_missing_footers.py \
    $DB_PATH_ORIGINAL $DB_PATH_CREGIT
fi
end_step

# ---------------------------------------------------------------------------
# Step 6 — persons DB
# ---------------------------------------------------------------------------
step "persons DB"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
  [ -f "$DB_PATH_CREGIT" ] || die "step 5 did not produce $DB_PATH_CREGIT"
  java -Xms1g -Xmx4g -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -jar -jar $PERSONS_JAR \
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
    $REPO_PATH_CREGIT $WORK/blame "$FILE_FILTER"
fi
end_step

# ---------------------------------------------------------------------------
# Step 9 — remap commits (cregit → original commit mapping)
# ---------------------------------------------------------------------------
step "remap commits"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
  [ -d "$WORK/blame" ] || die "step 8 did not run (blame dir missing)"
  java -Xms1g -Xmx4g -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -jar $REMAPCOMMITS_JAR \
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
    $REPO_COMMIT_URL "$FILE_FILTER"
fi
end_step

# ---------------------------------------------------------------------------
# Step 11 — generate unified Parquet dataset
# ---------------------------------------------------------------------------
step "generate Parquet dataset"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
  [ -f "$DB_PATH_CREGIT" ] || die "step 10 did not produce $DB_PATH_CREGIT"
  $PYTHON $CREGIT/generate_dataset/generate_dataset.py \
    --blame-dir "$WORK/blame" \
    --source-dir "$REPO_PATH_ORIGINAL" \
    --cregit-db "$DB_PATH_CREGIT" \
    --persons-db "$DB_PATH_PERSONS" \
    --output "$DATASET_PATH" \
    --repo-name "$REPO_NAME" \
    --verbose
fi
end_step
