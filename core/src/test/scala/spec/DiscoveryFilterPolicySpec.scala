package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Task
import sigil.TurnContext
import sigil.provider.ToolPolicy
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoveryFilter,
  DiscoverySpec,
  Effect,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

case class DiscoveryFilterPolicyStubInput(text: String = "") extends ToolInput derives RW

final class DiscoveryFilterPolicyStubTool(n: String) extends Tool {
  type Input = DiscoveryFilterPolicyStubInput
  type Output = TextToolOutput
  val io: ToolIO[DiscoveryFilterPolicyStubInput, TextToolOutput] = ToolIO.derived[DiscoveryFilterPolicyStubInput, TextToolOutput]
  override val name = ToolName.parse(n).fold(sys.error, identity)
  override val description = s"Stub $n"
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("test", n))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: DiscoveryFilterPolicyStubInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.Success(TextToolOutput(input.text)))
}

/**
 * Locks in [[DiscoveryFilter.passesPolicy]] semantics — the
 * discovery-side counterpart to the roster-side fold in
 * [[sigil.Sigil.effectiveToolNames]]. `Sigil.findCapabilities` runs
 * every candidate through this predicate after the finder returns,
 * so `Exclusive` / `Scoped` modes actually restrict what the agent
 * sees via `find_capability` (the `ToolPolicy` docstring claimed
 * this; only a roster-level fold previously enforced it).
 */
class DiscoveryFilterPolicySpec extends AnyWordSpec with Matchers {

  private val foo: Tool = new DiscoveryFilterPolicyStubTool("foo")
  private val bar: Tool = new DiscoveryFilterPolicyStubTool("bar")
  private val baz: Tool = new DiscoveryFilterPolicyStubTool("baz")
  private val all = List(foo, bar, baz)

  "DiscoveryFilter.passesPolicy" should {
    "let everything through under Standard, None, PureDiscovery, Active, Discoverable" in {
      val pass: List[ToolPolicy] = ToolPolicy.Standard :: ToolPolicy.None :: ToolPolicy.PureDiscovery ::
        ToolPolicy.Active(List(foo.name)) :: ToolPolicy.Discoverable(List(foo.name)) :: Nil
      pass.foreach { p =>
        all.filter(t => DiscoveryFilter.passesPolicy(t, p)).map(_.name) shouldBe all.map(_.name)
      }
    }

    "restrict to listed names under Exclusive" in {
      val policy = ToolPolicy.Exclusive(List(foo.name, baz.name))
      all.filter(t => DiscoveryFilter.passesPolicy(t, policy)).map(_.name) shouldBe List(foo.name, baz.name)
    }

    "restrict to listed names under Scoped" in {
      val policy = ToolPolicy.Scoped(List(bar.name))
      all.filter(t => DiscoveryFilter.passesPolicy(t, policy)).map(_.name) shouldBe List(bar.name)
    }

    "drop everything under Exclusive(Nil) / Scoped(Nil)" in {
      all.filter(t => DiscoveryFilter.passesPolicy(t, ToolPolicy.Exclusive(Nil))) shouldBe empty
      all.filter(t => DiscoveryFilter.passesPolicy(t, ToolPolicy.Scoped(Nil))) shouldBe empty
    }
  }
}
