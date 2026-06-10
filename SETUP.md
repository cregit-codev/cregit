# Setting up CreGit on NixOS

## 1. Clone the repository

```sh
git clone https://github.com/cregit/cregit
cd cregit
```

## 2. Set up the development environment (NixOS / devenv)

CreGit's dependencies are not all packaged in nixpkgs, so some custom derivations are needed.
The `devenv.nix` handles everything — including building all cregit components automatically.

### `.envrc`

```sh
#!/usr/bin/env bash
eval "$(devenv direnvrc)"
use devenv
```

```sh
direnv allow
```

On first load, devenv will build all components in order before entering the shell:

```
cregit:build:tokenizer
  → cregit:build:slickGitLog
    → cregit:build:persons
      → cregit:build:remapCommits
        → cregit:build:bfg
          → devenv:enterShell
```

On subsequent shell entries, each task checks if its artifact already exists and skips if so.
You can also trigger individual tasks manually:

```sh
devenv tasks run cregit:build:tokenizer
devenv tasks run cregit:build:slickGitLog
devenv tasks run cregit:build:persons
devenv tasks run cregit:build:remapCommits
devenv tasks run cregit:build:bfg
```

### Issues encountered and fixes (applied in `devenv.nix`)

| Issue | Fix |
|---|---|
| `pkgs.srcml` missing from nixpkgs | Custom derivation using the prebuilt Linux binary from GitHub releases + `autoPatchelfHook` |
| `HTML::FromText` missing from nixpkgs | Custom `buildPerlPackage` derivation from CPAN; `doCheck = false` required (tests fail in Nix sandbox) |
| sbt 0.13.x crashes on JDK 17+ (`Security Manager` removed) | Use `pkgs.jdk8` and set `SBT_OPTS="-Djava.security.manager=allow"` |
| `NoClassDefFoundError: sbt/Configuration` with sbt 0.13.7 | Upgrade to 0.13.18 in all `project/build.properties` files |
| `sqlite-jdbc:3.8.0-SNAPSHOT` not found in Sonatype | Replace with stable `3.45.3.0` in all three `build.sbt` files; remove Sonatype snapshots resolver |
| jgit resolver using `http://` | Updated to `https://` in all three `build.sbt` files |
| bfg pins sbt 0.13.13 (same class-loading bug) | Patched to 0.13.18 automatically by the `cregit:build:bfg` task on first clone |
| `String.lines` returns Java stream on JDK 11+ in `Commit.scala` | Patched to `.split("\n")` automatically by the `cregit:build:bfg` task on first clone |

## 3. Artifacts produced

After the devenv shell loads, the following are available:

| Artifact | Path |
|---|---|
| `srcml2token` | `tokenize/srcMLtoken/srcml2token` |
| `slickGitLog` JAR | `slickGitLog/target/scala-2.10/slickgitlog_2.10-0.1-SNAPSHOT-one-jar.jar` |
| `persons` JAR | `persons/target/scala-2.10/persons_2.10-0.1-SNAPSHOT-one-jar.jar` |
| `remapCommits` JAR | `remapCommits/target/scala-2.10/remapcommits_2.10-0.1-SNAPSHOT-one-jar.jar` |
| `bfg` JAR | `../bfg-repo-cleaner/bfg/target/bfg-<version>-blobexec-<hash>.jar` |

## Next steps

- Run the full cregit pipeline on a target repository (see `readme.org`)
