package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.provider.Provider

trait AbstractProviderSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  protected def provider: Task[Provider]

  getClass.getSimpleName should {
    "properly list models" in {
      provider.map { p =>
        scribe.info(s"Found models: ${p.models.map(_.name).mkString(", ")}")
        p.models should not be None
      }
    }
  }
}
