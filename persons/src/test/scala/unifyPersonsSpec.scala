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
import unifyPersons.Person

class unifyPersonsSpec extends FunSuite {

  def mkPerson(name: String, email: String): Person = {
    val (user, domain) = unifyPersons.splitEmail(email)
    val key = unifyPersons.dealWithSingleWords(name, email)
    new Person(name, key, email, email.toLowerCase, user, domain)
  }

  // strip_accents

  test("strip_accents removes combining diacritical marks") {
    assert(unifyPersons.strip_accents("José") === "Jose")
    assert(unifyPersons.strip_accents("Éléonore Müller") === "Eleonore Muller")
  }

  test("strip_accents maps the special characters ø ß æ ð") {
    assert(unifyPersons.strip_accents("Løvberg") === "Lovberg")
    assert(unifyPersons.strip_accents("Straße") === "Strasse")
    assert(unifyPersons.strip_accents("æ") === "ae")
    assert(unifyPersons.strip_accents("ð") === "o")
  }

  test("strip_accents leaves plain ascii untouched") {
    assert(unifyPersons.strip_accents("John Smith") === "John Smith")
  }

  // Person equality

  test("Persons with identical fields are equal and hash-equal") {
    val a = mkPerson("Jim Smith", "jim@example.com")
    val b = mkPerson("Jim Smith", "jim@example.com")
    assert(a === b)
    assert(a.hashCode === b.hashCode)
  }

  test("Persons differing in email are not equal") {
    val a = mkPerson("Jim Smith", "jim@example.com")
    val b = mkPerson("Jim Smith", "jim@other.org")
    assert(a !== b)
  }

  test("equal Persons deduplicate in a Set") {
    val a = mkPerson("Jim Smith", "jim@example.com")
    val b = mkPerson("Jim Smith", "jim@example.com")
    val c = mkPerson("Ann Lee", "ann@example.com")
    assert(Set(a, b, c).size === 2)
  }

  // splitEmail

  test("splitEmail splits user and domain") {
    assert(unifyPersons.splitEmail("a@b.com") === ("a", "b.com"))
  }

  test("splitEmail without @ yields empty domain") {
    assert(unifyPersons.splitEmail("localonly") === ("localonly", ""))
  }

  // dealWithSingleWords

  test("a name with a space becomes the lowercased name") {
    assert(unifyPersons.dealWithSingleWords("Jim Smith", "jim@x.com") === "jim smith")
  }

  test("a single-word name falls back to 'name at addon'") {
    assert(unifyPersons.dealWithSingleWords("root", "root@x.com") === "root at root@x.com")
  }

  test("accents are stripped before building the key") {
    assert(unifyPersons.dealWithSingleWords("José Núñez", "jose@x.com") === "jose nunez")
  }

  // unifyByEmail

  test("unifyByEmail merges groups sharing an email, transitively") {
    val jim1 = mkPerson("Jim Smith", "jim@example.com")
    val jim2 = mkPerson("James Smith", "jim@example.com") // shares email with jim1
    val jim3 = mkPerson("James Smith", "jsmith@work.org") // shares name-group with jim2
    val ann = mkPerson("Ann Lee", "ann@example.org")

    // groups as produced by the group-by-name step
    val groups = List(List(jim1), List(jim2, jim3), List(ann))

    val unified = unifyPersons.unifyByEmail(groups)

    assert(unified.size === 2)
    assert(unified.contains(Set(jim1, jim2, jim3)))
    assert(unified.contains(Set(ann)))
  }

  test("unifyByEmail keeps disjoint groups apart") {
    val a = mkPerson("Jim Smith", "jim@example.com")
    val b = mkPerson("Ann Lee", "ann@example.org")
    val unified = unifyPersons.unifyByEmail(List(List(a), List(b)))
    assert(unified === Set(Set(a), Set(b)))
  }

  test("unifyByEmail on empty input yields the empty set") {
    assert(unifyPersons.unifyByEmail(Nil) === Set.empty[Set[Person]])
  }

  // preferredName

  test("preferredName prefers a name containing a space") {
    val p = mkPerson("Jim Smith", "jim@example.com")
    assert(unifyPersons.preferredName(List(p)) === "Jim Smith")
  }

  test("preferredName falls back to the email for single-word names") {
    val p = mkPerson("root", "root@example.com")
    assert(unifyPersons.preferredName(List(p)) === "root@example.com")
  }

  test("preferredName only considers the first (most used) identity") {
    val single = mkPerson("root", "root@example.com")
    val full = mkPerson("Rudy Root", "rudy@example.com")
    assert(unifyPersons.preferredName(List(single, full)) === "root@example.com")
  }
}
