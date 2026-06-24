package cregit.blobexec

import com.madgag.git.bfg.model.{CommitNode, Footer => BfgFooter}
import org.eclipse.jgit.lib.PersonIdent
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8

/**
 * Pin our footer formatter to BFG's `CommitNode.add` output.
 *
 * Cross-checking with BFG's own `CommitNode` for every case is the contract
 * test: any divergence from BFG's bytes is a bug, since downstream cregit
 * tools (and any other consumer of these footers) were calibrated against
 * BFG's exact output.
 */
class FormerCommitFooterSpec extends AnyFunSuite with Matchers {

  private val sha = "0123456789abcdef0123456789abcdef01234567"

  private val ident = new PersonIdent("Tester", "t@example.org")

  private def bfg(message: String): String =
    CommitNode(ident, ident, message, UTF_8)
      .add(BfgFooter(FormerCommitFooter.Key, sha))
      .message

  private def check(message: String): Unit =
    FormerCommitFooter.append(message, sha) shouldEqual bfg(message)

  test("empty message") {
    check("")
  }

  test("subject only, no trailing newline") {
    check("Fix bug")
  }

  test("subject only, trailing newline") {
    check("Fix bug\n")
  }

  test("subject and body, no footers") {
    check("Fix bug\n\nLonger explanation goes here.\n")
  }

  test("body ending with a single existing footer") {
    check("Fix bug\n\nLonger explanation.\n\nSigned-off-by: Alice <a@x>\n")
  }

  test("body ending with multiple existing footers") {
    val msg =
      """Fix bug
        |
        |Longer explanation.
        |
        |Signed-off-by: Alice <a@x>
        |Reviewed-by: Bob <b@x>
        |""".stripMargin
    check(msg)
  }

  test("URL in last paragraph is parsed as a footer by BFG (known quirk)") {
    // Pinning behavior: BFG's pattern `([\p{Alnum}-]+): *(.*)` matches
    // "http://example.com:80/path". We match BFG here.
    check("See http://example.com:80/path")
  }

  test("message with only footer-shaped lines") {
    check("Signed-off-by: Alice <a@x>\n")
  }

  test("footer is always the last line of output") {
    val out = FormerCommitFooter.append("Fix bug\n", sha)
    out.linesIterator.toList.last shouldEqual s"${FormerCommitFooter.Key}: $sha"
  }
}
