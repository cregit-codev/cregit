#!/usr/bin/env perl

# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.

use strict;
use File::Basename;

my %declarations;
my %listDeclarations;

my %extensions = (
                  ".c" => 'C',
                  ".c++" => 'C++',
                  ".cc" => "C++",
                  ".cp" => 'C++',
                  ".cpp" => "C++",
                  ".cxx" => 'C++',
                  ".h" => "C",
                  ".h++" => 'C++',
                  ".hh" => 'C++',
                  ".hpp" => "C++",
                  ".java" => "Java",
                  ".am" => "M4",
                  ".ac" => "M4",
                  ".rs" => "Rust",
                 );

my $basedir = dirname($0);
$basedir = "." if ($basedir eq "");


my $srcMlparser = "$basedir/tokenizeSrcMl.pl";
my $m4Parser    = "$basedir/m4Tokenizer/m4.py";
my $rustParser  = "$basedir/rustTokenizer/target/release/rust_tokenizer";


my %parsers = ("C" => $srcMlparser,
               "C++" => $srcMlparser,
               "Java" => $srcMlparser,
               "M4" => $m4Parser,
               "Rust" => $rustParser,
              );


use Getopt::Long;

my $usage = "
Usage $0 [options] <sourcefilename> <outputfile>*

Options:
   --language=<C/C++/Java/m4/Rust>
   --position
   --srcml=<path>          (forwarded to srcML-based parsers only)
   --srcml2token=<path>    (forwarded to srcML-based parsers only)
   --ctags=<path>          (forwarded to srcML-based parsers only)
";


my $language = "";
my $verbose;
my $position = 0;
# srcML-specific options. Accepted here so a single caller (e.g. BFG_TOKENIZE_CMD)
# can pin the binary paths once and have them reach tokenizeSrcMl.pl without knowing
# which language each blob is. For non-srcML parsers (Rust, M4) these are ignored.
my $srcmlPath = "";
my $srcml2tokenPath = "";
my $ctagsPath = "";

GetOptions (
            "language=s"      => \$language,
            "position"        => \$position,
            "verbose"         => \$verbose,
            "srcml=s"         => \$srcmlPath,
            "srcml2token=s"   => \$srcml2tokenPath,
            "ctags=s"         => \$ctagsPath)
  or die($usage);


my $filename = shift;
my $output = shift;


# find language

if ($language eq "") {
    # autodetect
    Usage("File has no extension. You must provide one [$filename]") unless $filename =~ /(\.[a-z0-9\+]+)$/i;
    my $ext = lc($1);
    $language = $extensions{$ext};
    Usage("Unknown extension [$ext] in file [$filename]. You must provide language using --language option") unless defined $language and $language ne "";
    Usage("Unknown parser for extension [$ext] in file [$filename]. You must provide language using --language option") unless defined defined($parsers{language});
} else {
    # check the extension exists
    if (not (defined $parsers{$language}) or ($parsers{$language} eq "")) {
        Usage("We do not know what to do with this extension [$language]");
    }
}

Usage("filename not specified") if $filename eq "";


print STDERR "Tokenize $filename\n" if $verbose;

if ($output ne "") {
    open(OUT, ">$output") or die "Unable to create output file\n";
    select OUT;
}


Tokenize($language, $filename, $output);

if ($output) {
    close(OUT);
}

exit;

sub Tokenize {
    my ($language, $input, $output) = @_;

    print "Tokenizing [$language] [$input][$output]\n" if $verbose;

    my @command;

    push @command, $parsers{$language}, "--language=$language";

    if ($verbose) {
        push @command, "--verbose";
    }
    if ($position) {
        push @command, "--position";
    }
    if ($parsers{$language} eq $srcMlparser) {
        push @command, "--srcml=$srcmlPath"             if $srcmlPath ne "";
        push @command, "--srcml2token=$srcml2tokenPath" if $srcml2tokenPath ne "";
        push @command, "--ctags=$ctagsPath"             if $ctagsPath ne "";
    }
    push @command, $input;

    if ($output) {
        push @command, $output;
    }

    my $status = execute_command(@command);
    die "Unable to execute command @command" if $status != 0;
}

sub execute_command {
    my (@command) = @_;
    # make sure we have more than one element in the array
    #    otherwise system will use the shell to do the execution
    die "command (@command) seems to be missing parameters" unless (scalar(@command) > 1);

    print("Command to execute: ", join(" ", @command), "\n") if $verbose;

    my $status = system(@command);

    return $status;
}


sub Usage {

    print STDERR @_;
    die $usage;

}
