#!/usr/bin/env perl

# Golden-file tests for tokenizeSrcMl.pl, mirroring the srcMLtoken pattern:
# run the tokenizer over committed fixtures and compare against t/expected/.
#
# Requires srcml and ctags on PATH and tokenize/srcMLtoken/srcml2token built
# (cd tokenize/srcMLtoken && make); skipped otherwise.
#
# To regenerate the goldens after an intentional output change, run inside
# the devenv shell, from the repo root:
#   perl tokenize/tokenizeSrcMl.pl --ctags=ctags \
#     --srcml2token=$PWD/tokenize/srcMLtoken/srcml2token \
#     --position tokenize/srcMLtoken/tests/main.c tokenize/t/expected/main.c.token
#   perl tokenize/tokenizeSrcMl.pl --ctags=ctags \
#     --srcml2token=$PWD/tokenize/srcMLtoken/srcml2token \
#     tokenize/srcMLtoken/tests/main.c tokenize/t/expected/main.c.nopos.token
#   perl tokenize/tokenizeSrcMl.pl --ctags=ctags \
#     --srcml2token=$PWD/tokenize/srcMLtoken/srcml2token \
#     --position tokenize/srcMLtoken/tests/StringUtil.java tokenize/t/expected/StringUtil.java.token

use strict;
use warnings;
use Test::More;
use FindBin;
use File::Temp qw(tempdir);

my $script      = "$FindBin::Bin/../tokenizeSrcMl.pl";
my $srcml2token = "$FindBin::Bin/../srcMLtoken/srcml2token";
my $fixtures    = "$FindBin::Bin/../srcMLtoken/tests";
my $expected    = "$FindBin::Bin/expected";

plan skip_all => "srcml not on PATH"
    unless system("srcml --version >/dev/null 2>&1") == 0;
plan skip_all => "ctags not on PATH"
    unless system("ctags --version >/dev/null 2>&1") == 0;
plan skip_all => "srcml2token not built (cd tokenize/srcMLtoken && make)"
    unless -x $srcml2token;

plan tests => 8;

my $workdir = tempdir(CLEANUP => 1);

sub slurp {
    my ($file) = @_;
    open(my $fh, '<', $file) or die "unable to read [$file]: $!";
    local $/;
    my $content = <$fh>;
    return defined $content ? $content : '';
}

# returns (exit status, stdout, stderr)
sub run_tokenizer {
    my (@args) = @_;
    my $out = "$workdir/stdout";
    my $err = "$workdir/stderr";
    my $status = system("perl '$script' --ctags=ctags --srcml2token='$srcml2token' "
                        . join(' ', @args) . " > '$out' 2> '$err'");
    return ($status, slurp($out), slurp($err));
}

# C fixture, with token positions
{
    my ($status, $out, $err) = run_tokenizer("--position", "'$fixtures/main.c'");
    is($status, 0, "tokenizing main.c with --position succeeds");
    is($out, slurp("$expected/main.c.token"),
       "main.c --position output matches the golden file");
}

# C fixture, without positions
{
    my ($status, $out, $err) = run_tokenizer("'$fixtures/main.c'");
    is($status, 0, "tokenizing main.c without --position succeeds");
    is($out, slurp("$expected/main.c.nopos.token"),
       "main.c output matches the golden file");
}

# Java fixture (language autodetected from the .java extension)
{
    my ($status, $out, $err) = run_tokenizer("--position", "'$fixtures/StringUtil.java'");
    is($status, 0, "tokenizing StringUtil.java succeeds");
    is($out, slurp("$expected/StringUtil.java.token"),
       "StringUtil.java output matches the golden file");
}

# unknown extension without --language is an error
{
    my $bogus = "$workdir/mystery.xyz";
    open(my $fh, '>', $bogus) or die $!;
    print $fh "int main() {}\n";
    close $fh;

    my ($status, $out, $err) = run_tokenizer("'$bogus'");
    isnt($status, 0, "unknown extension exits non-zero");
    like($err, qr/Unknown extension/, "unknown extension reported on stderr");
}
