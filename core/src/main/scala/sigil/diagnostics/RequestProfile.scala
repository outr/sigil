package sigil.diagnostics

import fabric.Str
import fabric.define.{DefType, Definition}
import fabric.rw.*
import lightdb.id.Id
import sigil.event.Event
import sigil.provider.FeatureId

/**
 * Per-section token-budget breakdown of a single
 * [[sigil.provider.ConversationRequest]] as it would land on the wire.
 *
 * `sections` carries the system-prompt sections plus the wire-level
 * pieces (frames, tool roster). `total` is the sum across all sections.
 * `frames` carries an optional per-frame breakdown for digging into
 * which conversation events are dominant.
 *
 * Used by the Phase 0 profiling instrumentation that drives the
 * shedding-policy design — see `benchmark/src/main/scala/bench/contextprofile/`.
 */
case class RequestProfile(total: Int,
                          sections: Map[ProfileSection, Int],
                          frames: Vector[FrameProfile],
                          insights: List[ContextManagementInsight] = Nil) derives RW

/** Discriminator for the parts of a wire request a `RequestProfile`
  * counts. One case per entry in
  * [[sigil.provider.ContextSections.all]] — the list the renderer and
  * the profiler both drive from — plus the framing pieces (frames,
  * tool roster) that live outside the system prompt on the wire, plus
  * [[ProfileSection.Feature]] for the open-identity contributions of
  * [[sigil.provider.ContextFeature]]s. */
enum ProfileSection {
  case ToolFramingPrefix
  case ModeBlock
  case CurrentTopic
  case PreviousTopics
  case Instructions
  case CriticalMemories
  case Summaries
  case Memories
  case Information
  case Roles
  case ActiveSkills
  case RecentTools
  case RepeatedToolCalls
  case SuggestedTools
  case DiscoveredCapabilities
  case ExtraContext
  case ParticipantContext
  case GreetingHint
  case Frames
  case ToolRoster

  /** A registered [[sigil.provider.ContextFeature]]'s contribution. The
    * feature's own id is the key, so one feature reports one number no
    * matter how many blocks it emitted or where they landed. */
  case Feature(id: FeatureId)
}

object ProfileSection {

  private val Prefix = "ProfileSection."
  private val FeaturePrefix = Prefix + "Feature:"

  /** The closed cases, in declaration order. [[Feature]] is open and
    * therefore not enumerable — consumers that want the features a run
    * actually produced read the keys of [[RequestProfile.sections]]. */
  val values: Array[ProfileSection] = Array(
    ToolFramingPrefix, ModeBlock, CurrentTopic, PreviousTopics, Instructions, CriticalMemories,
    Summaries, Memories, Information, Roles, ActiveSkills, RecentTools, RepeatedToolCalls,
    SuggestedTools, DiscoveredCapabilities, ExtraContext, ParticipantContext, GreetingHint,
    Frames, ToolRoster
  )

  private val byName: Map[String, ProfileSection] = values.iterator.map(s => s.toString -> s).toMap
  private val byLowerName: Map[String, ProfileSection] = byName.map { case (n, s) => n.toLowerCase -> s }

  /** A closed case by name, as the generated enum accessor behaved. */
  def valueOf(name: String): ProfileSection =
    byName.getOrElse(name, throw new IllegalArgumentException(s"enum case not found: $name"))

  /** The wire discriminator: the class-chain form fabric's enum
    * derivation produces (`ProfileSection.Memories`), with the open
    * feature id carried after a colon. */
  def wireName(section: ProfileSection): String = section match {
    case Feature(id) => FeaturePrefix + id.value
    case other       => Prefix + other.toString
  }

  def parse(name: String): ProfileSection =
    if (name.startsWith(FeaturePrefix)) Feature(FeatureId(name.substring(FeaturePrefix.length)))
    else {
      val leaf = if (name.startsWith(Prefix)) name.substring(Prefix.length) else name
      byName.getOrElse(leaf, byLowerName.getOrElse(leaf.toLowerCase,
        throw RWException(s"Unknown ProfileSection: $name")))
    }

  /** Hand-written rather than derived because [[Feature]] carries a
    * payload: fabric's derivation would fall off the enum path onto the
    * sealed-trait path, rewriting every existing discriminator into an
    * object. This keeps the string form byte-identical to what the
    * derived enum RW produced and only adds the feature spelling.
    *
    * The definition stays `Poly` for the same reason
    * [[sigil.conversation.ContextKey]]'s stays `Obj`: a `Str`
    * definition would silently move `Map[ProfileSection, Int]` off
    * fabric's array-of-pairs encoding onto a JSON object. */
  given rw: RW[ProfileSection] = RW.from[ProfileSection](
    r = section => Str(wireName(section)),
    w = json => parse(json.asString),
    d = Definition(
      DefType.Poly((values.toList.map(v => Prefix + v.toString) :+ (Prefix + "Feature"))
        .map(_ -> Definition(DefType.Null))*),
      className = Some("sigil.diagnostics.ProfileSection")
    )
  )
}

/** Per-frame token contribution. `kind` is one of `Text`, `ToolCall`,
  * `System`, `Reasoning` — the [[sigil.conversation.ContextFrame]]
  * variants — so reports can group by frame type. A settled tool
  * transaction is ONE `ToolCall` entry carrying both its args and its
  * result content. */
case class FrameProfile(kind: String,
                        sourceEventId: Id[Event],
                        tokens: Int) derives RW
