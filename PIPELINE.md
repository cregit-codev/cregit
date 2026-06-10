# Running the CreGit Pipeline on the Linux Kernel

## Prerequisites

- devenv shell loaded (`direnv allow` inside `cregit/`)
- All components built (handled automatically by devenv tasks on first load)

## Scale warning

The Linux kernel has ~60,000 `.c`/`.h` files across its entire history.
Running the full pipeline will take **days** and require **hundreds of GB** of disk space.
Consider running on a single subsystem first (e.g. `kernel/`, `fs/`, `drivers/net/`).

## Environment variables

Set these once before running any step:

```sh
CREGIT=/home/elliancarlos/Projects/mestrado-space/linux-kernel/cregit
BFG=/home/elliancarlos/Projects/mestrado-space/linux-kernel/bfg-repo-cleaner/bfg/target/bfg-1.12.16-SNAPSHOT-blobexec-fbf55a1-dirty.jar
LINUX=/home/elliancarlos/Projects/mestrado-space/linux-kernel/linux
WORK=/home/elliancarlos/Projects/mestrado-space/linux-kernel/cregit-output

mkdir -p $WORK/memo $WORK/blame $WORK/html
```

## Step 1 — Bare clone

bfg requires a bare repository and modifies it in place.
Work on a clone, never on the original.

```sh
git clone --bare $LINUX $WORK/linux.git
```

## Step 2 — Tokenize (create the cregit view repo)

Replaces every `.c`/`.h` blob with its token-level representation.
This is the slowest step.

```sh
export BFG_MEMO_DIR=$WORK/memo
export BFG_TOKENIZE_CMD="$CREGIT/tokenize/tokenizeSrcMl.pl \
  --srcml2token=$CREGIT/tokenize/srcMLtoken/srcml2token \
  --srcml=$(which srcml) \
  --ctags=$(which ctags)"

java -jar $BFG \
  "--blob-exec:$CREGIT/tokenizeByBlobId/tokenBySha.pl=\.[ch]\$" \
  --no-blob-protection $WORK/linux.git
```

## Step 3 — Git log DB (original repo)

Extracts commit metadata from the original Linux repo into a SQLite database.

```sh
java -jar $CREGIT/slickGitLog/target/scala-2.10/slickgitlog_2.10-0.1-SNAPSHOT-one-jar.jar \
  $WORK/linux-original.db $LINUX
```

## Step 4 — Git log DB (cregit repo)

Same as step 3 but for the tokenized repo produced by bfg.

```sh
java -jar $CREGIT/slickGitLog/target/scala-2.10/slickgitlog_2.10-0.1-SNAPSHOT-one-jar.jar \
  $WORK/linux-cregit.db $WORK/linux.git
```

## Step 5 — Persons DB

Extracts and unifies author/committer identities into a SQLite database
and an Excel spreadsheet.

```sh
java -jar $CREGIT/persons/target/scala-2.10/persons_2.10-0.1-SNAPSHOT-one-jar.jar \
  $LINUX $WORK/linux-persons.xls $WORK/linux-persons.db
```

## Step 6 — Blame

Runs `git blame` on the cregit repo to map each token back to the commit that introduced it.

```sh
perl $CREGIT/blameRepo/blameRepoFiles.pl --verbose \
  --formatBlame=$CREGIT/blameRepo/formatBlame.pl \
  $WORK/linux.git $WORK/blame '\.[ch]$'
```

## Step 7 — Remap commits

Creates a mapping table from cregit commit hashes back to original Linux commit hashes.

```sh
java -jar $CREGIT/remapCommits/target/scala-2.10/remapcommits_2.10-0.1-SNAPSHOT-one-jar.jar \
  $WORK/linux-cregit.db $WORK/linux.git
```

## Step 8 — Generate HTML views

Produces an interactive HTML file per source file showing token-level authorship,
linked to the original commits on GitHub.

```sh
perl $CREGIT/prettyPrint/prettyPrintFiles.pl --verbose \
  $WORK/linux-cregit.db $WORK/linux-persons.db \
  $LINUX $WORK/blame $WORK/html \
  https://github.com/torvalds/linux/commit/ '\.[ch]$'
```

## Output

| Path | Contents |
|---|---|
| `$WORK/linux.git` | Bare cregit view repo (tokenized blobs) |
| `$WORK/linux-original.db` | Commit metadata for the original repo |
| `$WORK/linux-cregit.db` | Commit metadata for the cregit repo |
| `$WORK/linux-persons.db` | Unified author/committer identities |
| `$WORK/linux-persons.xls` | Same, as a spreadsheet |
| `$WORK/blame/` | Raw blame output per file |
| `$WORK/html/` | Interactive HTML token-level authorship views |
