package cregit.blobexec

object FormerCommitFooter {

  val Key = "Former-commit-id"

  // Matches BFG's Footer.FooterPattern (Footer.scala): one-or-more alnum-or-hyphen,
  // a colon, optional spaces, then arbitrary tail. Whole-line match.
  private val FooterPattern = """([\p{Alnum}-]+): *(.*)""".r

  /**
   * Append the `Former-commit-id: <origSha>` footer to a commit message,
   * reproducing the byte-for-byte output of BFG's
   * `CommitNode.add(Footer(Key, origSha))`.
   *
   * Rule (BFG `Commit.scala`):
   *   newMessage = message + "\n" + (if footers.isEmpty then "\n" else "") + footer
   *
   * `footers` is the set of footer-pattern lines from the trailing paragraph
   * (the substring starting at the last `\n\n`; whole message if no blank line).
   */
  def append(message: String, origSha: String): String = {
    val lastBreak = message.lastIndexOf("\n\n")
    val trailingBlock = if (lastBreak < 0) message else message.drop(lastBreak)
    val hasFooters = trailingBlock.linesIterator.exists {
      case FooterPattern(_, _) => true
      case _                   => false
    }
    val separator = if (hasFooters) "\n" else "\n\n"
    message + separator + Key + ": " + origSha
  }
}
