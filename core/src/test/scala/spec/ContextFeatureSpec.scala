package spec

import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Task
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.diagnostics.{ProfileSection, RequestProfiler}
import sigil.provider.*
import sigil.tokenize.HeuristicTokenizer
import sigil.tool.core.CoreTools

import java.util.concurrent.atomic.AtomicInteger

/**
 * The feature layer's contract: an app registers a Task-effectful
 * contribution under an open id, it compiles down to sections the
 * renderer and the profiler already understand, it takes part in the
 * shed cascade and per-section budgets on the same terms as any
 * section, and disabling it by id removes its bytes entirely.
 */
class ContextFeatureSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("context-feature-conv")
  private val modelId = Model.id("test", "context-feature")

  private val request = ConversationRequest(
    conversationId = convId,
    model = TestSigil.testModel(modelId),
    instructions = Instructions(),
    turnInput = TurnInput(conversationId = convId),
    currentMode = ConversationMode,
    currentTopic = TestTopicEntry,
    generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
    tools = CoreTools.all,
    chain = List(TestUser, TestAgent)
  )

  private val resolved = ResolvedReferences(Vector.empty, Vector.empty, Vector.empty)

  private def baseContext: SectionContext =
    SectionContext(request, resolved, discoveredCapabilitiesPromptCap = 25, now = Timestamp().value)

  private def render(features: List[ContextFeature], placement: Placement): String = {
    val sections = ContextSections.all ++ ContextFeatures.sections(features)
    val ctx = ContextFeatures.evaluate(features, baseContext).sync()
    ContextSections.render(sections, placement, ctx)
  }

  /**
   * Just the features' own contribution, without the rest of the prompt.
   */
  private def renderFeatures(features: List[ContextFeature], placement: Placement): String = {
    val ctx = ContextFeatures.evaluate(features, baseContext).sync()
    ContextSections.render(ContextFeatures.sections(features), placement, ctx)
  }

  private val statusId = FeatureId("erpStatus")

  /**
   * An app feature that consults a live source.
   */
  private class StatusFeature(status: String,
                              override val placement: Placement = Placement.VolatileTail,
                              override val budget: Option[Int] = None)
    extends ContextFeature {
    val calls = new AtomicInteger(0)
    val id: FeatureId = statusId
    def compute(ctx: SectionContext): Task[List[FeatureBody]] = Task {
      calls.incrementAndGet()
      List(FeatureBody.prose(s"\n== Connectivity ==\nThe ERP connection is $status.\n"))
    }
  }

  "A registered feature" should {
    "render its computed body into the placement it declares" in {
      val feature = new StatusFeature("down")
      render(List(feature), Placement.VolatileTail) should include("The ERP connection is down.")
      render(List(feature), Placement.StablePrefix) should not include "The ERP connection is down."
    }

    "compute exactly once for a context however many sections read it" in {
      val feature = new StatusFeature("up")
      val ctx = ContextFeatures.evaluate(List(feature), baseContext).sync()
      val sections = ContextFeatures.sections(List(feature))
      sections.foreach(_.rendered(ctx))
      sections.foreach(_.rendered(ctx))
      feature.calls.get() shouldBe 1
    }

    "attribute its tokens in the profiler under its own id" in {
      val feature = new StatusFeature("down")
      val sections = ContextSections.all ++ ContextFeatures.sections(List(feature))
      val ctx = ContextFeatures.evaluate(List(feature), baseContext).sync()
      val profile = RequestProfiler.profileWith(ctx, HeuristicTokenizer, _.description, sections)
      profile.sections.getOrElse(ProfileSection.Feature(statusId), 0) should be > 0
    }

    "report one number however many blocks it emitted, or where" in {
      val split = new ContextFeature {
        val id: FeatureId = FeatureId("splitFeature")
        def placement: Placement = Placement.StablePrefix
        def compute(ctx: SectionContext): Task[List[FeatureBody]] = Task(List(
          FeatureBody.prose("\nStable usage guidance for the module.\n"),
          FeatureBody.prose("\nLive status: nominal.\n").at(Placement.VolatileTail)
        ))
      }
      val sections = ContextFeatures.sections(List(split))
      val ctx = ContextFeatures.evaluate(List(split), baseContext).sync()

      ContextSections.render(sections, Placement.StablePrefix, ctx) should
        include("Stable usage guidance for the module.")
      ContextSections.render(sections, Placement.VolatileTail, ctx) should include("Live status: nominal.")

      val profile = RequestProfiler.profileWith(ctx, HeuristicTokenizer, _.description, sections)
      profile.sections.keys.count {
        case ProfileSection.Feature(_) => true
        case _ => false
      } shouldBe 1
    }

    "fail its own turn only — a throwing feature contributes nothing" in {
      val broken = new ContextFeature {
        val id: FeatureId = FeatureId("brokenFeature")
        def placement: Placement = Placement.VolatileTail
        def compute(ctx: SectionContext): Task[List[FeatureBody]] =
          Task.error(new RuntimeException("live source unreachable"))
      }
      val healthy = new StatusFeature("up")
      val features = List(broken, healthy)
      val ctx = ContextFeatures.evaluate(features, baseContext).sync()
      ctx.featureBodies(broken.id) shouldBe Nil
      ContextSections.render(ContextFeatures.sections(features), Placement.VolatileTail, ctx) should
        include("The ERP connection is up.")
    }
  }

  "A disabled feature" should {
    "leave the request byte-for-byte identical to one with no features" in {
      val feature = new StatusFeature("down")
      TestSigil.setContextFeatures(List(feature))
      TestSigil.setDisabledFeatures(Set(statusId))
      try {
        TestSigil.enabledContextFeatures shouldBe Nil
        val ctx = ContextFeatures.evaluate(TestSigil.enabledContextFeatures, baseContext).sync()
        Placement.values.foreach { placement =>
          ContextSections.render(TestSigil.resolvedContextSections, placement, ctx) shouldBe
            ContextSections.render(ContextSections.all, placement, baseContext)
        }
        feature.calls.get() shouldBe 0
      } finally
        TestSigil.resetContextFeatures()
    }
  }

  "A feature declaring a budget" should {
    "trim its entries exactly as a budgeted section does" in {
      val lines = (1 to 8).toList.map(i => s"- catalog entry number $i, long enough to cost real tokens\n")
      def catalog(cap: Option[Int]): ContextFeature = new ContextFeature {
        val id: FeatureId = FeatureId("catalogFeature")
        def placement: Placement = Placement.VolatileTail
        override def budget: Option[Int] = cap
        def compute(ctx: SectionContext): Task[List[FeatureBody]] =
          Task(List(FeatureBody.entries("\n== Catalog ==\n", lines)))
      }
      val full = renderFeatures(List(catalog(None)), Placement.VolatileTail)
      val cap = HeuristicTokenizer.count(full) / 2
      val trimmed = renderFeatures(List(catalog(Some(cap))), Placement.VolatileTail)

      HeuristicTokenizer.count(trimmed) should be <= cap
      trimmed should include("catalog entry number 1")
      trimmed should not include "catalog entry number 8"
    }
  }

  "A feature declaring a shed stage" should {
    "join the curator's cascade exactly once" in {
      val sheddable = new ContextFeature {
        val id: FeatureId = FeatureId("sheddableFeature")
        def placement: Placement = Placement.VolatileTail
        override def shedStage: Option[Int] = Some(9)
        override def shed: Option[TurnInput => TurnInput] = Some(t => t.copy(memories = Vector.empty))
        def compute(ctx: SectionContext): Task[List[FeatureBody]] = Task(Nil)
      }
      val cascade = ContextSections.shedCascade(ContextSections.all ++ ContextFeatures.sections(List(sheddable)))
      cascade.count(_.id == ProfileSection.Feature(sheddable.id)) shouldBe 1
      cascade.last.id shouldBe ProfileSection.Feature(sheddable.id)
    }

    "fail startup when it declares a stage it cannot act on" in {
      val broken = new ContextFeature {
        val id: FeatureId = FeatureId("stagelessFeature")
        def placement: Placement = Placement.VolatileTail
        override def shedStage: Option[Int] = Some(9)
        def compute(ctx: SectionContext): Task[List[FeatureBody]] = Task(Nil)
      }
      an[IllegalArgumentException] should be thrownBy
        ContextSections.shedCascade(ContextFeatures.sections(List(broken)))
    }
  }
}
