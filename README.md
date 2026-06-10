# Cregit

![Cregit logo](./logos/cregit.png)

## Preliminaries

- Code is written in Scala, C++ and Perl.
- For Scala code, use `sbt` to build and run programs. It is the easiest.
- This code has only being tested under Linux, but it should run under MacOS.

## Prerequisites

| | | | 
|-|-|-|
| srcml | https://www.srcml.org/              | Make sure srcml is in path |
| ctags | https://github.com/universal-ctags  | Make sure ctags is in path |
| bfg   | https://github.com/dmgerman/bfg-repo-cleaner/tree/blobexec | Download this branch |

### bfg

Obtain the above bfg branch:

```sh
git clone https://github.com/dmgerman/bfg-repo-cleaner.git --branch blobexec
```

Build in the bfg root directory:

```sh
sbt
bfg/assembly
```

### Dependencies

For each module, its dependencies are documented in their corresponding README file.

As an example, on Debian 9 the following packages must be installed:

```
cmake libarchive-dev libxml++2.6-dev libxml2-dev libcurl4-openssl-dev libxslt1-dev libboost-all-dev libantlr-dev libssl-dev libxerces-c-dev exuberant-ctags libdbi-perl libjgit-java libhtml-fromtext-perl libset-scalar-perl libdbd-sqlite3-perl
```

## How to build

Compile _slickGitLog_, _persons_, _remapCommits_ with `sbt` in their directories (`sbt` can be found at
https://www.scala-sbt.org/download.html repo):

```sh
sbt one-jar
```

Use `make` to compile [srcMLtoken](./tokenize/srcMLtoken).

Perl scripts can be run without compilation.

## How to use

This is the workflow to process a git repository with cregit, and to generate the HTML views of its contributions.

- It assumes that the cregit-repository will be created in `/tmp/xournal`
- The original repository is located at `/home/dmg/git.hacking.mine/xournal`

### a. Create the view repository

To tokenize the files we require to create some environment variables to communicate with the tokenizing script:

| | |
|-|-|
| `BFG_MEMO_DIR`     | directory to use for memoization of tokenized files  |
| `BFG_TOKENIZE_CMD` | command to use to tokenize, might include parameters |

Example:

- use the `tokenizeByBlobId/tokenBySha.pl` to do the tokenization.
- `tokenBySha.pl` will execute.

```sh
./tokenizeSrcMl.pl --srcml2token=<path to srcml2token> --srcml=<path to srcml> --ctags=<path to ctags>
```

Run it as:

```sh
export BFG_MEMO_DIR=/tmp/memo
export BFG_TOKENIZE_CMD=/home/dmg/git.dmg/cregit-scala/tokenize/tokenizeSrcMl.pl --srcml2token=/home/dmg/git.dmg/cregit-scala/tokenize/srcMLtoken/srcml2token --srcml=srcml --ctags=/usr/local/bin/ctags
java -jar bfg-cregit.jar '--blob-exec:/home/dmg/git.dmg/cregit-scala/tokenizeByBlobId/tokenBySha.pl=.[ch]$' --no-blob-protection /path/repo
```

### b. Create the history database for the original repo

```sh
java -jar slickGitLog.jar /tmp/xournal-original.db /home/dmg/git.hacking.mine/xournal
```

### c. Create the history database for the cregit repo

```sh
java -jar slickGitLog.jar /tmp/xournal-cregit.db /tmp/xournal
```

### d. Create the persons database

```sh
java -jar persons.jar /home/dmg/git.hacking.mine/xournal /tmp/xournal.xls /tmp/xournal-persons.db
```

### e. Create blame of cregit files

```sh
perl blameRepoFiles.pl --verbose --formatBlame=./formatBlame.pl /tmp/xournal /tmp/blame '\.[ch]$'
```

### f. Create the table with the map from newcommits to commits

```sh
java -jar remapCommits.jar /tmp/xournal-cregit.db /tmp/xournal
```

### g. Create html version of the files

Example:

```sh
perl ./prettyPrintFiles.pl --verbose /tmp/xournal-cregit.db /tmp/xournal-persons.db ~/git.hacking.mine/xournal /tmp/blame /tmp/html https://github.com/dmgerman/xournal-next/commit/ '\.[ch]$'
```

## Contributing

Contributions are welcome! Please read our [contributing guide](CONTRIBUTING.md) before opening an issue or pull request.

For larger changes, please open an issue first to discuss the proposal.

## License

The license of Cregit is [GPL-3.0+](LICENSE.md).

## TODO

- use preferred name in html files
- create a driver program for processing an entire repository
- customize programs to read a JSON file with configuration?
