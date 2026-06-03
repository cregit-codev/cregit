# blobExec

Tokenization driver for the cregit pipeline (step 2). Rewrites every matching
blob in a repo's history by piping it through a per-blob command. Built on
upstream `com.madgag:bfg-library`, replacing the old `dmgerman/bfg-repo-cleaner@blobexec`
fork.

## Build

Needs sbt 1.x + Scala 2.13 + JDK 21 (provided by the project's `devenv.nix`).

```sh
sbt assembly
```

Produces `target/scala-2.13/blobExec-0.1.0-assembly.jar`.

## Use

```sh
java -jar blobExec-0.1.0-assembly.jar <repo.git> <command> <fileMaskRegex>
```

For each blob whose filename matches `<fileMaskRegex>`, runs `<command>` with the
blob bytes on stdin and env `BFG_BLOB` (object id) + `BFG_FILENAME` set, then
replaces the blob with the command's stdout. Non-zero exit or unchanged output
leaves the blob alone. All commits are rewritten (no blob protection).

In the pipeline, `<command>` is `tokenizeByBlobId/tokenBySha.pl`, which also reads
`BFG_MEMO_DIR` and `BFG_TOKENIZE_CMD` from the environment. The memo cache is
content-addressed by `sha1(blob)`, so caches from prior runs are reused as-is.
