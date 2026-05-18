package bench.contextprofile

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.{Sigil, SpaceId, TurnContext}
import sigil.conversation.{ContextFrame, Conversation, MemorySource, TopicEntry, TurnInput}
import sigil.conversation.compression.{NoOpBlockExtractor, NoOpContextCompressor, NoOpMemoryRetriever, StandardContextCurator}
import sigil.db.{DefaultSigilDB, Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.event.{Event, Message, MessageRole}
import sigil.information.Information
import sigil.participant.{AgentParticipantId, Participant, ParticipantId}
import sigil.provider.Provider
import sigil.signal.{EventState, Signal}
import sigil.tool.{InMemoryToolFinder, ToolFinder}
import sigil.tool.model.ResponseContent
import sigil.vector.{NoOpVectorIndex, VectorIndex}
import lightdb.time.Timestamp

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/**
 * Scale + wire-log replay benchmark — sanity-checks the curator
 * pipeline against the kinds of conversation shapes that have
 * historically hidden performance regressions (bug cluster
 * #142–#147 plus the #151 investigation).
 *
 * Two modes:
 *
 * 1. **Synthetic scale (default).** Without arguments, the bench
 *    runs the pipeline at 1 K / 5 K / 25 K event scales. Times
 *    `publishHistorical`, `framesFor`, and a full `curate(...)`
 *    pass with [[StandardContextCurator]] defaults (so
 *    `maxFramesPerTurn = 5000` kicks in past 5 K). Linear-ish
 *    growth across the scales is the "all good" shape; any phase
 *    that hits double-digit seconds at 25 K is a red flag for
 *    O(N²) or similar.
 *
 * 2. **Wire-log replay** — when a path is supplied, parses
 *    `chat/completions` request bodies from a Sigil wire-log
 *    JSONL (`sigil.wire.log.dir/<SuiteName>.jsonl`), takes the
 *    largest seen `messages` array (the latest turn's full
 *    transcript), publishes the messages as historical events,
 *    times the same pipeline phases. Useful for verifying the
 *    framework handles the SHAPE of a real conversation
 *    (system-prompt overhead, tool-message density, mixed
 *    content) — but it does NOT capture the conversation's
 *    pre-trim history, so events you see in this mode are
 *    bounded by whatever the curator already trimmed before
 *    dispatch (usually a few thousand at most).
 *
 *    For genuine 50 K+ event scale repros, use the synthetic
 *    mode; the wire log isn't a snapshot of the durable event
 *    log.
 *
 * Run:
 *   sbt "benchmark/runMain bench.contextprofile.WireLogReplayBench"
 *   sbt "benchmark/runMain bench.contextprofile.WireLogReplayBench /path/to/wire.jsonl"
 *
 * Bug #151 followup.
 */
object WireLogReplayBench {

  case object BenchUser extends ParticipantId { override val value: String = "bench-user" }
  case object BenchAgent extends AgentParticipantId { override val value: String = "bench-agent" }

  /**
   * Lightweight Sigil instance — just enough to publish events + curate.
   */
  private case class BenchSigil() extends Sigil {
    override type DB = DefaultSigilDB
    override protected def buildDB(directory: Option[java.nio.file.Path],
                                   storeManager: lightdb.store.CollectionManager,
                                   appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
      new DefaultSigilDB(directory, storeManager, appUpgrades)
    override protected def signalRegistrations: List[RW[? <: Signal]] = Nil
    override protected def participantIds: List[RW[? <: ParticipantId]] = List(
      RW.static(BenchUser),
      RW.static(BenchAgent)
    )
    override protected def spaceIds: List[RW[? <: SpaceId]] = Nil
    override protected def participants: List[RW[? <: Participant]] = Nil
    override val findTools: ToolFinder = InMemoryToolFinder(Nil)
    override def getInformation(id: Id[Information]): Task[Option[Information]] = Task.pure(None)
    override def putInformation(information: Information): Task[Unit] = Task.unit
    override def compressionMemorySpace(conversationId: Id[Conversation]): Task[Option[SpaceId]] = Task.pure(None)
    override def embeddingProvider: EmbeddingProvider = NoOpEmbeddingProvider
    override def vectorIndex: VectorIndex = NoOpVectorIndex
    override def providerFor(modelId: Id[Model], chain: List[ParticipantId]): Task[Provider] =
      Task.error(new UnsupportedOperationException("bench provider not configured"))
  }

  private val topic = TopicEntry(sigil.conversation.Topic.id("bench-topic"), label = "bench", summary = "bench")

  private val benchModelId: Id[Model] = Model.id("bench/bench-model")

  private def benchModel(contextLength: Long = 100_000L): Model = Model(
    canonicalSlug = "bench/bench-model",
    huggingFaceId = "",
    name = "bench-model",
    displayName = Some("bench-model"),
    description = "",
    contextLength = contextLength,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "Unknown",
      instructType = None
    ),
    pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
    topProvider = ModelTopProvider(contextLength = Some(contextLength), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    defaultParameters = ModelDefaultParameters(),
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    modified = Timestamp(),
    _id = benchModelId
  )

  def main(args: Array[String]): Unit = {
    val wireLogPath = args.headOption.map(Paths.get(_))
    // Fresh per-run DB path — bypass any stale tool records from
    // prior runs / tests that might use incompatible registrations.
    val dbPath = s"db/bench/wire-log-replay-${System.currentTimeMillis()}"
    _root_.profig.Profig("sigil.dbPath").store(dbPath)
    val sigil = BenchSigil()
    sigil.cache.replace(List(benchModel())).sync()

    wireLogPath match {
      case Some(path) if Files.exists(path) =>
        println(s"[wire-log replay] reading $path")
        val events = extractEventsFromWireLog(path)
        println(s"[wire-log replay] parsed ${events.size} events from wire log")
        runScenario(sigil, events, label = "wire-log-replay")
      case Some(path) =>
        System.err.println(s"[wire-log replay] file not found: $path")
        sys.exit(2)
      case None =>
        println("[scale bench] no wire-log path supplied — running synthetic scale (1K/5K/25K)")
        List(1_000, 5_000, 25_000).foreach { n =>
          val convId = Conversation.id(s"scale-$n-${rapid.Unique()}")
          val events = synthEvents(convId, n)
          runScenario(sigil, events, label = s"synth-$n")
        }
    }
  }

  /**
   * Extract the most-recently-seen `messages` array from a
   * sequence of `/v1/chat/completions` requests in the wire log
   * (later requests subsume earlier ones since each turn's
   * messages array is a superset of the previous), then convert
   * each message to a synthetic Sigil Event.
   */
  private def extractEventsFromWireLog(path: java.nio.file.Path): Vector[Event] = {
    val convId = Conversation.id(s"wire-log-replay-${rapid.Unique()}")
    val lines = scala.io.Source.fromFile(path.toFile, StandardCharsets.UTF_8.name()).getLines().toVector
    val messages: Vector[fabric.Json] = lines.iterator.flatMap { line =>
      scala.util.Try {
        val rec = fabric.io.JsonParser(line)
        val kind = rec.get("kind").map(_.asString).getOrElse("")
        val url = rec.get("url").map(_.asString).getOrElse("")
        if (kind == "request" && url.endsWith("/v1/chat/completions")) {
          rec.get("body").flatMap(_.get("messages")).map(_.asVector).getOrElse(Vector.empty)
        } else Vector.empty[fabric.Json]
      }.getOrElse(Vector.empty[fabric.Json])
    }.toVector

    // The chat-completions transcript grows turn-over-turn — every
    // request includes the full history up to that point. Take the
    // LARGEST seen array (= the latest turn's full transcript).
    val transcript: Vector[fabric.Json] = if (messages.isEmpty) Vector.empty
    else {
      lines.iterator.flatMap { line =>
        scala.util.Try {
          val rec = fabric.io.JsonParser(line)
          val kind = rec.get("kind").map(_.asString).getOrElse("")
          val url = rec.get("url").map(_.asString).getOrElse("")
          if (kind == "request" && url.endsWith("/v1/chat/completions")) {
            rec.get("body").flatMap(_.get("messages")).map(_.asVector)
          } else None
        }.toOption.flatten
      }.maxByOption(_.size).getOrElse(Vector.empty)
    }

    transcript.zipWithIndex.flatMap { case (msg, idx) =>
      val role = msg.get("role").map(_.asString).getOrElse("")
      val content: String = msg.get("content") match {
        case Some(c) if c.isStr => c.asString
        case Some(c) if c.isArr =>
          c.asVector.iterator.flatMap(_.get("text").map(_.asString)).mkString(" ")
        case _ => ""
      }
      if (content.isEmpty || role == "system") None
      else Some {
        // Tool-role messages on the wire carry a `tool_call_id`
        // back to the parent ToolInvoke. Reconstructing that parent
        // event tree from a replayed transcript isn't worth the
        // wiring for a pipeline-perf bench — map tool messages to
        // Standard so the framework's "every Tool event needs
        // origin" invariant holds. The bench measures shape +
        // size, not semantic fidelity.
        val participantId: ParticipantId =
          if (role == "assistant" || role == "tool") BenchAgent else BenchUser
        Message(
          participantId = participantId,
          conversationId = convId,
          topicId = topic.id,
          content = Vector(ResponseContent.Text(content)),
          state = EventState.Complete,
          role = MessageRole.Standard,
          timestamp = Timestamp(System.currentTimeMillis() + idx)
        )
      }
    }
  }

  /**
   * Synthesise a vector of `count` Text-only Message events for
   * scale benchmarking. ~200 chars per event matches typical
   * Claude-Code session content.
   */
  private def synthEvents(convId: Id[Conversation], count: Int): Vector[Event] = {
    val padding = " content payload " * 12 // ~200 chars
    (0 until count).toVector.map { i =>
      val (pid, role) = if (i % 2 == 0) (BenchUser: ParticipantId, MessageRole.Standard)
      else (BenchAgent: ParticipantId, MessageRole.Standard)
      Message(
        participantId = pid,
        conversationId = convId,
        topicId = topic.id,
        content = Vector(ResponseContent.Text(s"event $i:$padding")),
        state = EventState.Complete,
        role = role,
        timestamp = Timestamp(System.currentTimeMillis() + i)
      )
    }
  }

  private def runScenario(sigil: Sigil, events: Vector[Event], label: String): Unit = {
    val convId = events.headOption.map(_.conversationId)
      .getOrElse(Conversation.id(s"$label-empty"))
    val curator = StandardContextCurator(
      sigil = sigil,
      blockExtractor = NoOpBlockExtractor,
      memoryRetriever = NoOpMemoryRetriever,
      compressor = NoOpContextCompressor
    )

    println(s"\n=== $label ===")
    println(s"  events: ${events.size}")

    sigil.withDB(_.conversations.transaction(_.upsert(Conversation(
      _id = convId,
      topics = List(topic)
    )))).sync()

    val tImport0 = System.currentTimeMillis()
    sigil.publishHistorical(events, convId).sync()
    val tImport = System.currentTimeMillis() - tImport0
    println(f"  publishHistorical:  $tImport%6d ms")

    val tFramesFor0 = System.currentTimeMillis()
    val frames = sigil.framesFor(convId).sync()
    val tFramesFor = System.currentTimeMillis() - tFramesFor0
    println(f"  framesFor:          $tFramesFor%6d ms (returned ${frames.size} frames)")

    val tCurate0 = System.currentTimeMillis()
    val turnInput = curator.curate(convId, benchModelId, chain = List(BenchUser, BenchAgent)).sync()
    val tCurate = System.currentTimeMillis() - tCurate0
    println(f"  curate (full):      $tCurate%6d ms (TurnInput.frames=${turnInput.frames.size})")
  }
}
