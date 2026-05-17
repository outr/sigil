package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Pins the removal of `sigil.provider.CodingMode`. The case object existed
 * only as template code — its sole purpose was advertising the `"coding"`
 * wire discriminator so app-defined coding modes could agree on a name.
 * Once the polymorphic Mode RW uses [[sigil.provider.Mode.name]] directly
 * (rather than the Scala class name), nothing about that shared
 * discriminator depends on a framework-shipped instance — apps just name
 * their own mode `"coding"` and the wire payload matches.
 *
 * A runtime `Class.forName` probe is the cleanest guard: it FAILS on a
 * trunk that still ships `sigil.provider.CodingMode` (the class loader
 * finds the case object's runtime class), and passes once the file is
 * removed.
 */
class StockCodingModeRemovalSpec extends AnyWordSpec with Matchers {

  "sigil.provider.CodingMode" should {
    "no longer exist (removed as redundant template code)" in {
      val loader = classOf[sigil.provider.Mode].getClassLoader
      val attempt = try {
        Class.forName("sigil.provider.CodingMode$", false, loader)
        Some("sigil.provider.CodingMode$")
      } catch {
        case _: ClassNotFoundException => None
      }
      attempt shouldBe None
    }
  }
}
