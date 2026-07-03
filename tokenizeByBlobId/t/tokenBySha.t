#!/usr/bin/env perl

# Tests for tokenBySha.pl, the per-blob driver invoked by blobExec. It is
# argv-less by design: the blob arrives on stdin and everything else comes
# from BFG_* environment variables. A stub tokenizer stands in for the real
# tokenize command, so neither srcml nor ctags is needed.
#
# Note: the script anchors its scratch files to tokenizeByBlobId/build/ (it
# cleans them up itself); leftovers there from a failed run are scratch, not
# fixtures.

use strict;
use warnings;
use Test::More tests => 13;
use FindBin;
use File::Temp qw(tempdir);
use Digest::SHA qw(sha1_hex);

my $script  = "$FindBin::Bin/../tokenBySha.pl";
my $workdir = tempdir(CLEANUP => 1);

# stub tokenizer: prints a banner, its --language argument, then the file
# it was given (the temp copy of the blob). Deterministic output only.
my $stub = "$workdir/stub-tokenizer.sh";
{
    open(my $fh, '>', $stub) or die $!;
    print $fh "#!/bin/sh\necho STUB-TOKENIZER\necho \"\$1\"\ncat \"\$2\"\n";
    close $fh;
    chmod 0755, $stub or die $!;
}

sub slurp {
    my ($file) = @_;
    open(my $fh, '<', $file) or die "unable to read [$file]: $!";
    local $/;
    my $content = <$fh>;
    return defined $content ? $content : '';
}

# runs tokenBySha.pl with the given env overrides and stdin content;
# returns (exit status, stdout, stderr)
sub run_tokenbysha {
    my ($content, %env) = @_;
    my $in  = "$workdir/stdin";
    my $out = "$workdir/stdout";
    my $err = "$workdir/stderr";
    open(my $fh, '>', $in) or die $!;
    print $fh $content;
    close $fh;

    local %ENV = %ENV;
    while (my ($k, $v) = each %env) {
        if (defined $v) { $ENV{$k} = $v } else { delete $ENV{$k} }
    }

    my $status = system("perl '$script' < '$in' > '$out' 2> '$err'");
    return ($status, slurp($out), slurp($err));
}

my $memoDir = tempdir(CLEANUP => 1);
my $content = "int a;\nint b;\n";
my $sha1 = sha1_hex($content);
my $memoFile = "$memoDir/" . substr($sha1, 0, 2) . "/" . substr($sha1, 2, 2) . "/$sha1";

# first run: tokenizes via the stub and memoizes
{
    my ($status, $out, $err) = run_tokenbysha($content,
        BFG_MEMO_DIR     => $memoDir,
        BFG_TOKENIZE_CMD => $stub,
        BFG_BLOB         => "0" x 40,
        BFG_FILENAME     => "foo.c",
    );
    is($status, 0, "first run succeeds");
    like($out, qr/^STUB-TOKENIZER\n--language=C\n/,
         "stub is invoked with --language=C for a .c file");
    is($out, "STUB-TOKENIZER\n--language=C\n$content",
       "blob content reaches the tokenizer");
    ok(-f $memoFile, "output is memoized under xx/yy/<sha1> of the blob content");
    is(slurp($memoFile), $out, "memoized file matches stdout");
}

# second run: served from the memo without invoking the tokenize command
{
    my ($status, $out, $err) = run_tokenbysha($content,
        BFG_MEMO_DIR     => $memoDir,
        BFG_TOKENIZE_CMD => "/bin/false",
        BFG_BLOB         => "0" x 40,
        BFG_FILENAME     => "foo.c",
    );
    is($status, 0, "cache hit succeeds even with a broken tokenize command");
    is($out, slurp($memoFile), "cache hit replays the memoized output");
}

# extension mapping: .cpp maps to C++
{
    my ($status, $out, $err) = run_tokenbysha("class X {};\n",
        BFG_MEMO_DIR     => $memoDir,
        BFG_TOKENIZE_CMD => $stub,
        BFG_BLOB         => "1" x 40,
        BFG_FILENAME     => "foo.cpp",
    );
    is($status, 0, ".cpp run succeeds");
    like($out, qr/^STUB-TOKENIZER\n--language=C\+\+\n/, ".cpp maps to --language=C++");
}

# unknown extension dies
{
    my ($status, $out, $err) = run_tokenbysha($content,
        BFG_MEMO_DIR     => $memoDir,
        BFG_TOKENIZE_CMD => $stub,
        BFG_BLOB         => "2" x 40,
        BFG_FILENAME     => "foo.zzz",
    );
    isnt($status, 0, "unknown extension exits non-zero");
    like($err, qr/unknown file extension/, "unknown extension reported on stderr");
}

# missing memo dir dies before doing any work
{
    my ($status, $out, $err) = run_tokenbysha($content,
        BFG_MEMO_DIR     => undef,
        BFG_TOKENIZE_CMD => $stub,
        BFG_BLOB         => "3" x 40,
        BFG_FILENAME     => "foo.c",
    );
    isnt($status, 0, "missing BFG_MEMO_DIR exits non-zero");
}

# empty BFG_FILENAME dies
{
    my ($status, $out, $err) = run_tokenbysha($content,
        BFG_MEMO_DIR     => $memoDir,
        BFG_TOKENIZE_CMD => $stub,
        BFG_BLOB         => "4" x 40,
        BFG_FILENAME     => "",
    );
    isnt($status, 0, "empty BFG_FILENAME exits non-zero");
}
