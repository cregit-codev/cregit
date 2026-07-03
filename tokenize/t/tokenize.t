#!/usr/bin/env perl

# Tests for the tokenize.pl language dispatcher. It forwards to
# tokenizeSrcMl.pl (run with its built-in defaults, which is also how the
# dispatcher runs in production), so its output must be byte-identical to
# calling tokenizeSrcMl.pl directly with the same flags.

use strict;
use warnings;
use Test::More;
use FindBin;
use File::Temp qw(tempdir);

my $dispatcher = "$FindBin::Bin/../tokenize.pl";
my $direct     = "$FindBin::Bin/../tokenizeSrcMl.pl";
my $fixtures   = "$FindBin::Bin/../srcMLtoken/tests";

plan skip_all => "srcml not on PATH"
    unless system("srcml --version >/dev/null 2>&1") == 0;
plan skip_all => "srcml2token not built (cd tokenize/srcMLtoken && make)"
    unless -x "$FindBin::Bin/../srcMLtoken/srcml2token";

plan tests => 7;

my $workdir = tempdir(CLEANUP => 1);

sub slurp {
    my ($file) = @_;
    open(my $fh, '<', $file) or die "unable to read [$file]: $!";
    local $/;
    my $content = <$fh>;
    return defined $content ? $content : '';
}

# returns (exit status, stdout, stderr)
sub run_script {
    my ($script, @args) = @_;
    my $out = "$workdir/stdout";
    my $err = "$workdir/stderr";
    my $status = system("perl '$script' " . join(' ', @args) . " > '$out' 2> '$err'");
    return ($status, slurp($out), slurp($err));
}

# dispatch by explicit --language forwards flags and produces the same
# output as running tokenizeSrcMl.pl directly
{
    my ($dstatus, $dout) = run_script($dispatcher, "--language=C", "--position", "'$fixtures/main.c'");
    my ($sstatus, $sout) = run_script($direct, "--language=C", "--position", "'$fixtures/main.c'");
    is($dstatus, 0, "dispatching main.c as C succeeds");
    is($sstatus, 0, "direct tokenizeSrcMl.pl run succeeds");
    is($dout, $sout, "dispatcher output is byte-identical to the direct run");
}

# language autodetection from the file extension picks the same parser
{
    my ($astatus, $aout) = run_script($dispatcher, "--position", "'$fixtures/main.c'");
    my ($estatus, $eout) = run_script($dispatcher, "--language=C", "--position", "'$fixtures/main.c'");
    is($astatus, 0, "autodetecting .c succeeds");
    is($aout, $eout, "autodetected output matches the explicit --language=C run");
}

# unknown --language is rejected
{
    my ($status, $out, $err) = run_script($dispatcher, "--language=Nope", "'$fixtures/main.c'");
    isnt($status, 0, "unknown --language exits non-zero");
    like($err, qr/We do not know what to do/, "unknown language reported on stderr");
}
