/*

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

*/

import org.scalatest.FunSuite

class remapCommitsSpec extends FunSuite {

  val cid = "a" * 40
  val originalCid = "0123456789abcdef0123456789abcdef01234567"

  test("footer on the last line yields the original commit id") {
    val message = s"some subject\n\nFormer-commit-id: $originalCid"
    assert(remapCommits.extractOriginalCid(cid, message) === originalCid)
  }

  test("footer as the only line yields the original commit id") {
    val message = s"Former-commit-id: $originalCid"
    assert(remapCommits.extractOriginalCid(cid, message) === originalCid)
  }

  test("footer not on the last line falls back to cid") {
    val message = s"subject\nFormer-commit-id: $originalCid\ntrailing line"
    assert(remapCommits.extractOriginalCid(cid, message) === cid)
  }

  test("message without a footer falls back to cid") {
    assert(remapCommits.extractOriginalCid(cid, "just a subject") === cid)
  }

  test("empty message falls back to cid") {
    assert(remapCommits.extractOriginalCid(cid, "") === cid)
  }

  test("message of only newlines falls back to cid") {
    // String.split("\n") on "\n" returns an empty array; .last throws and
    // the catch turns it into "" -> no match -> cid
    assert(remapCommits.extractOriginalCid(cid, "\n") === cid)
    assert(remapCommits.extractOriginalCid(cid, "\n\n\n") === cid)
  }

  test("uppercase hex footer is not recognized (regex is lowercase-only)") {
    val upper = originalCid.toUpperCase
    assert(remapCommits.extractOriginalCid(cid, s"Former-commit-id: $upper") === cid)
  }

  test("short sha in footer is not recognized") {
    val message = "Former-commit-id: 0123456789abcdef"
    assert(remapCommits.extractOriginalCid(cid, message) === cid)
  }
}
