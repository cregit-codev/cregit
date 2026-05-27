{ pkgs, ... }:

let
  # srcml is not in nixpkgs — package the official prebuilt Linux binary
  srcml = pkgs.stdenv.mkDerivation {
    pname = "srcml";
    version = "1.1.0";
    src = pkgs.fetchurl {
      url = "https://github.com/srcML/srcML/releases/download/v1.1.0/srcml_1.1.0-1_linux_amd64.tar.bz2";
      sha256 = "1hh9ii5fr4gv6xn81g99a9nr29x166hvmjkci4ygjriwkj63g7mz";
    };
    nativeBuildInputs = [ pkgs.autoPatchelfHook ];
    buildInputs = [ pkgs.stdenv.cc.cc.lib ];  # provides libstdc++.so.6
    sourceRoot = ".";
    installPhase = ''
      mkdir -p $out/bin $out/lib $out/share
      install -m755 bin/srcml $out/bin/
      cp -P lib/libsrcml.so.1 lib/libsrcml.so.1.1.0 $out/lib/
      cp -r share/. $out/share/
    '';
  };

  # Email::Find is not in nixpkgs — build it from CPAN
  # Required as a transitive dependency of HTML::FromText (Email::Find::addrspec)
  EmailFind = pkgs.perlPackages.buildPerlPackage rec {
    pname = "Email-Find";
    version = "0.10";
    src = pkgs.fetchurl {
      url = "https://cpan.metacpan.org/authors/id/M/MI/MIYAGAWA/Email-Find-0.10.tar.gz";
      sha256 = "sha256-KaqgB9DepKjY23eM8ZHq3sqRxOmIO6So4txNVOjM8g4=";
    };
    propagatedBuildInputs = with pkgs.perlPackages; [ EmailValid ];
    doCheck = false;
    meta.description = "Find email addresses in arbitrary text";
  };

  # HTML::FromText is not in nixpkgs — build it from CPAN
  HTMLFromText = pkgs.perlPackages.buildPerlPackage rec {
    pname = "HTML-FromText";
    version = "2.07";
    src = pkgs.fetchurl {
      url = "https://cpan.metacpan.org/authors/id/R/RJ/RJBS/HTML-FromText-2.07.tar.gz";
      sha256 = "1b93zria8is1kcanwaldyzjcijqcsgrbasvlnmzp1gh584r11q65";
    };
    buildInputs = with pkgs.perlPackages; [ TestMore ];
    propagatedBuildInputs = with pkgs.perlPackages; [ TextAutoformat ] ++ [ EmailFind ];
    doCheck = false;  # tests fail in Nix sandbox (no network)
    meta.description = "Mark up text as HTML";
  };

  # Perl with all required modules in @INC — using withPackages so scripts
  # can find DBI, DBD::SQLite, etc. without needing PERL5LIB set manually
  perlEnv = pkgs.perl.withPackages (p: [
    p.DBI
    p.DBDSQLite
    EmailFind
    HTMLFromText
    p.HTMLParser        # provides HTML::Entities
    p.SetScalar
    p.TextAutoformat
  ]);
in
{
  packages = [
    pkgs.git
    pkgs.gnumake
    pkgs.gcc

    srcml                    # srcML: converts source code to XML (tokenization)
    pkgs.universal-ctags     # required by tokenization pipeline
    pkgs.xercesc             # -lxerces-c for compiling srcMLtoken
    pkgs.sbt                 # build tool for Scala modules
    pkgs.sqlite
    pkgs.git-filter-repo     # filter linux repo to a subsystem before running cregit
    perlEnv                  # Perl + all required modules in @INC
  ];

  languages.java  = { enable = true; jdk.package = pkgs.jdk8; };
  languages.scala = { enable = true; };

  # sbt 0.13.x uses Security Manager API, removed in JDK 17+

  tasks = {
    # ── 1. C++ tokenizer ─────────────────────────────────────────────────────
    "cregit:build:tokenizer" = {
      description = "Build srcml2token C++ binary";
      before = [ "devenv:enterShell" ];
      exec = ''
        TARGET="$DEVENV_ROOT/tokenize/srcMLtoken/srcml2token"
        if [ -f "$TARGET" ]; then
          echo "cregit:build:tokenizer: already built, skipping."
        else
          echo "cregit:build:tokenizer: building srcml2token..."
          make -C "$DEVENV_ROOT/tokenize/srcMLtoken"
        fi
      '';
    };

    # ── 2. slickGitLog JAR ───────────────────────────────────────────────────
    "cregit:build:slickGitLog" = {
      description = "Build slickGitLog one-jar";
      after  = [ "cregit:build:tokenizer" ];
      before = [ "devenv:enterShell" ];
      exec = ''
        TARGET="$DEVENV_ROOT/slickGitLog/target/scala-2.10/slickgitlog_2.10-0.1-SNAPSHOT-one-jar.jar"
        if [ -f "$TARGET" ]; then
          echo "cregit:build:slickGitLog: already built, skipping."
        else
          echo "cregit:build:slickGitLog: running sbt one-jar..."
          cd "$DEVENV_ROOT/slickGitLog" && sbt one-jar
        fi
      '';
    };

    # ── 3. persons JAR ───────────────────────────────────────────────────────
    "cregit:build:persons" = {
      description = "Build persons one-jar";
      after  = [ "cregit:build:slickGitLog" ];
      before = [ "devenv:enterShell" ];
      exec = ''
        TARGET="$DEVENV_ROOT/persons/target/scala-2.10/persons_2.10-0.1-SNAPSHOT-one-jar.jar"
        if [ -f "$TARGET" ]; then
          echo "cregit:build:persons: already built, skipping."
        else
          echo "cregit:build:persons: running sbt one-jar..."
          cd "$DEVENV_ROOT/persons" && sbt one-jar
        fi
      '';
    };

    # ── 4. remapCommits JAR ──────────────────────────────────────────────────
    "cregit:build:remapCommits" = {
      description = "Build remapCommits one-jar";
      after  = [ "cregit:build:persons" ];
      before = [ "devenv:enterShell" ];
      exec = ''
        TARGET="$DEVENV_ROOT/remapCommits/target/scala-2.10/remapcommits_2.10-0.1-SNAPSHOT-one-jar.jar"
        if [ -f "$TARGET" ]; then
          echo "cregit:build:remapCommits: already built, skipping."
        else
          echo "cregit:build:remapCommits: running sbt one-jar..."
          cd "$DEVENV_ROOT/remapCommits" && sbt one-jar
        fi
      '';
    };

    # ── 5. bfg (custom blob-exec fork) ──────────────────────────────────────
    "cregit:build:bfg" = {
      description = "Clone and build the dmgerman/bfg-repo-cleaner blobexec fork";
      after  = [ "cregit:build:remapCommits" ];
      before = [ "devenv:enterShell" ];
      exec = ''
        BFG_DIR="$DEVENV_ROOT/../bfg-repo-cleaner"
        BFG_COMMIT_SCALA="$BFG_DIR/bfg-library/src/main/scala/com/madgag/git/bfg/model/Commit.scala"

        # Clone if not present
        if [ ! -d "$BFG_DIR" ]; then
          echo "cregit:build:bfg: cloning bfg-repo-cleaner (blobexec branch)..."
          git clone https://github.com/dmgerman/bfg-repo-cleaner --branch blobexec "$BFG_DIR"

          # Fix 1: sbt 0.13.13 → 0.13.18 (class-loading bug)
          sed -i 's/sbt.version=0.13.13/sbt.version=0.13.18/' \
            "$BFG_DIR/project/build.properties"

          # Fix 2: String.lines returns Java stream on JDK 11+ — use split instead
          sed -i \
            's/message\.lines\.toStream/message.split("\\n").toStream/g' \
            "$BFG_COMMIT_SCALA"
          sed -i \
            's/message\.drop(lastParagraphBreak)\.lines/message.drop(lastParagraphBreak).split("\\n")/g' \
            "$BFG_COMMIT_SCALA"
        fi

        # Build if JAR not present
        if ls "$BFG_DIR/bfg/target/bfg-"*.jar 2>/dev/null | grep -q .; then
          echo "cregit:build:bfg: already built, skipping."
        else
          echo "cregit:build:bfg: running sbt bfg/assembly..."
          cd "$BFG_DIR" && sbt clean bfg/assembly
        fi
      '';
    };
  };
}


