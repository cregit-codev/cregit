# Running the CreGit Pipeline on a Linux Subsystem (e.g. IIO)

Running cregit on the full Linux kernel takes days and hundreds of GB.
For research on a specific subsystem, the approach is to first create a
filtered clone containing only that subsystem's history, then run the
normal cregit pipeline on it.

The IIO subsystem (`drivers/iio/`) has ~763 `.c`/`.h` files and ~2,556
commits — very manageable. The full pipeline completed in a few hours.

## Prerequisites

- devenv shell loaded (`direnv allow` inside `cregit/`)
- `git-filter-repo` available (included in the devenv packages)

## NixOS-specific fixes applied to cregit source

These patches were required to run cregit on NixOS with srcml 1.1.0:

| File | Issue | Fix |
|---|---|---|
| All `.pl` scripts | Shebang `#!/usr/bin/perl` — `/usr/bin/perl` does not exist on NixOS | Changed to `#!/usr/bin/env perl` |
| `tokenizeByBlobId/tokenBySha.pl` | Temp file created without extension; srcml 1.1.0 requires a file extension even when `--language` is given | Use `tempfile(..., SUFFIX => ".$fileExt")` so the temp file gets the original file's extension |
| `devenv.nix` Perl setup | Individual `pkgs.perlPackages.*` entries do not populate `PERL5LIB`; scripts fail with `Can't locate DBI.pm` | Use `pkgs.perl.withPackages` to build a single Perl env with all modules in `@INC` |
| `devenv.nix` Perl setup | `HTML::FromText` requires `Email::Find` (not in nixpkgs) | Added custom `EmailFind` `buildPerlPackage` derivation from CPAN (MIYAGAWA/Email-Find-0.10) |
| `devenv.nix` Perl setup | `HTML::FromText` also requires `HTML::Entities` (from `HTML::Parser`) | Added `p.HTMLParser` to `perlEnv` |

## Note on bare vs non-bare repositories

`blameRepoFiles.pl` and `prettyPrintFiles.pl` use `git ls-files` and check for
files on disk — both require a **non-bare** working clone. The bare repos
created in steps 1–3 are used for git log and remap steps only. Non-bare
clones are created in step 7 before blame and HTML generation.

## Environment variables

```sh
CREGIT=/home/elliancarlos/Projects/mestrado-space/linux-kernel/cregit
BFG=/home/elliancarlos/Projects/mestrado-space/linux-kernel/bfg-repo-cleaner/bfg/target/bfg-1.12.16-SNAPSHOT-blobexec-fbf55a1-dirty.jar
LINUX=/home/elliancarlos/Projects/mestrado-space/linux-kernel/linux
SUBSYSTEM=drivers/iio
WORK=/home/elliancarlos/Projects/mestrado-space/linux-kernel/cregit-iio

mkdir -p $WORK/memo $WORK/blame $WORK/html
```

## Step 1 — Create a filtered clone

Extract only the IIO subsystem history into a new bare repo.
This rewrites history to include only commits that touch `drivers/iio/`.

```sh
git clone --bare $LINUX $WORK/linux-iio-original.git
git -C $WORK/linux-iio-original.git filter-repo --path $SUBSYSTEM/ --force
```

## Step 2 — Clone the filtered repo for bfg

bfg modifies the repo in place, so work on a copy of the filtered repo.

```sh
git clone --bare $WORK/linux-iio-original.git $WORK/linux-iio-cregit.git
```

## Step 3 — Tokenize (create the cregit view repo)

Replaces every `.c`/`.h` blob with its token-level representation.

```sh
export BFG_MEMO_DIR=$WORK/memo
export BFG_TOKENIZE_CMD="$CREGIT/tokenize/tokenizeSrcMl.pl \
  --srcml2token=$CREGIT/tokenize/srcMLtoken/srcml2token \
  --srcml=$(which srcml) \
  --ctags=$(which ctags)"

java -jar $BFG \
  "--blob-exec:$CREGIT/tokenizeByBlobId/tokenBySha.pl=\.[ch]\$" \
  --no-blob-protection $WORK/linux-iio-cregit.git
```

After a successful run, bfg prints:

```
In total, 12262 object ids were changed.
BFG run is complete! When ready, run:
  git reflog expire --expire=now --all && git gc --prune=now --aggressive
```

Run the suggested gc command to compact the repo:

```sh
git --git-dir=$WORK/linux-iio-cregit.git reflog expire --expire=now --all
git --git-dir=$WORK/linux-iio-cregit.git gc --prune=now --aggressive
```

## Step 4 — Git log DB (original filtered repo)

```sh
java -jar $CREGIT/slickGitLog/target/scala-2.10/slickgitlog_2.10-0.1-SNAPSHOT-one-jar.jar \
  $WORK/iio-original.db $WORK/linux-iio-original.git
```

## Step 5 — Git log DB (cregit repo)

```sh
java -jar $CREGIT/slickGitLog/target/scala-2.10/slickgitlog_2.10-0.1-SNAPSHOT-one-jar.jar \
  $WORK/iio-cregit.db $WORK/linux-iio-cregit.git
```

## Step 6 — Persons DB

```sh
java -jar $CREGIT/persons/target/scala-2.10/persons_2.10-0.1-SNAPSHOT-one-jar.jar \
  $WORK/linux-iio-original.git $WORK/iio-persons.xls $WORK/iio-persons.db
```

## Step 7 — Create non-bare working clones

`blameRepoFiles.pl` and `prettyPrintFiles.pl` require a working tree (non-bare repo).
Clone both bare repos before running blame or HTML generation:

```sh
git clone $WORK/linux-iio-original.git $WORK/linux-iio-original
git clone $WORK/linux-iio-cregit.git   $WORK/linux-iio-cregit
```

## Step 8 — Blame

Runs `git blame` on every `.c`/`.h` file in the cregit repo and writes
a compact blame record per file into `$WORK/blame/`.

```sh
perl $CREGIT/blameRepo/blameRepoFiles.pl --verbose \
  --formatBlame=$CREGIT/blameRepo/formatBlame.pl \
  $WORK/linux-iio-cregit $WORK/blame '\.[ch]$'
```

## Step 9 — Remap commits

Creates a mapping table from cregit commit hashes back to original Linux commit hashes.

```sh
java -jar $CREGIT/remapCommits/target/scala-2.10/remapcommits_2.10-0.1-SNAPSHOT-one-jar.jar \
  $WORK/iio-cregit.db $WORK/linux-iio-cregit.git
```

## Step 10 — Generate HTML views

Produces one interactive HTML file per source file showing token-level authorship,
linked to the original commits on GitHub.

```sh
perl $CREGIT/prettyPrint/prettyPrintFiles.pl --verbose \
  $WORK/iio-cregit.db $WORK/iio-persons.db \
  $WORK/linux-iio-original $WORK/blame $WORK/html \
  https://github.com/torvalds/linux/commit/ '\.[ch]$'
```

## Output

| Path | Contents |
|---|---|
| `$WORK/linux-iio-original.git` | Filtered bare repo (IIO history only) |
| `$WORK/linux-iio-original` | Non-bare working clone (used for HTML generation) |
| `$WORK/linux-iio-cregit.git` | Tokenized cregit view (bare) |
| `$WORK/linux-iio-cregit` | Non-bare working clone (used for blame) |
| `$WORK/iio-original.db` | Commit metadata for the original IIO repo |
| `$WORK/iio-cregit.db` | Commit metadata for the cregit IIO repo |
| `$WORK/iio-persons.db` | Unified author/committer identities (389 persons) |
| `$WORK/iio-persons.xls` | Same, as a spreadsheet |
| `$WORK/blame/` | Raw blame output per file (763 files) |
| `$WORK/html/` | Interactive HTML token-level authorship views (754/763 files) |

The 9 files that failed HTML generation have edge-case tokens that the
`prettyPrint-author.pl` parser does not handle; all other files are complete.

## Adapting to other subsystems

Change `SUBSYSTEM` and `WORK` to target a different subsystem:

```sh
SUBSYSTEM=fs/ext4   WORK=.../cregit-ext4
SUBSYSTEM=net/core  WORK=.../cregit-net-core
SUBSYSTEM=kernel    WORK=.../cregit-kernel
```
