package sigil.maintenance

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.{CapabilityResults, ErrorClassification, ErrorContext, Event, Message, MessageDisposition, MessageRole, ToolInvoke, ToolResults}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

/**
 * Boot-time reconciler for two corruption shapes in `db.events`:
 *
 *   1. **Dangling `ToolInvoke`** — a tool_call with no paired
 *      `MessageRole.Tool` / `ToolResults` / `CapabilityResults`
 *      result. Every later context build sees an unpaired call and
 *      synthesizes an ephemeral failure frame; the agent reads it and
 *      aborts. Reconciled by inserting a synthetic Tool-role failure
 *      `Message` paired via `origin`.
 *   2. **Orphan result** — a result whose `origin` points at a
 *      `ToolInvoke._id` absent from the conversation. Deleted.
 *
 * Idempotent: it first purges its own prior output (events tagged
 * `Event.source == SourceTag`), then re-scans, so a later reconciler
 * always supersedes an earlier one. Run after the DB opens and before
 * WS / Notice ingress, so no live turn races it.
 */
object OrphanReconciler:

  /** Stamped on every event this reconciler creates. */
  val SourceTag: String = "historic-orphan-reconciled"

  private val FailureContentTemplate: String =
    "Reconciled from a prior session — the original tool call completed " +
    "without emitting a paired result (framework defect, since fixed). " +
    "No retry necessary; this row exists only to satisfy the wire " +
    "protocol's tool_call/tool_result pairing requirement."

  /** Counts of each reconciliation action taken. */
  final case class Report(synthesizedFailures: Int, deletedOrphans: Int):
    def total: Int = synthesizedFailures + deletedOrphans
    def isEmpty: Boolean = total == 0

  /** Reconcile `sigil`'s event store. Safe to invoke at every boot. */
  def run(sigil: Sigil): Task[Report] =
    sigil.withDB(_.events.transaction { tx =>
      for
        all <- tx.stream.toList
        // Purge prior synthesized events first so each boot's
        // reconciler is the source of truth.
        previouslySynth = all.collect { case e if e.source.contains(SourceTag) => e._id }
        _ <- rapid.Stream.emits(previouslySynth).evalMap(tx.delete).drain
        cleaned <- tx.stream.toList
        byConv = cleaned.groupBy(_.conversationId)
        report <- rapid.Stream.emits(byConv.toList)
                    .evalMap { case (convId, events) => reconcileConversation(tx, convId, events) }
                    .toList
                    .map(_.foldLeft(Report(0, 0)) { (acc, r) =>
                      Report(acc.synthesizedFailures + r.synthesizedFailures,
                             acc.deletedOrphans + r.deletedOrphans)
                    })
      yield report
    })

  private def reconcileConversation(tx: lightdb.transaction.CollectionTransaction[Event, Event.type],
                                    convId: Id[Conversation],
                                    events: List[Event]): Task[Report] =
    // Every ToolInvoke is a candidate regardless of its state field —
    // boot-time isolation guarantees no in-flight calls, so pairing by
    // `origin` is the sole authority for "did this call get a result?".
    val allInvokes: Map[Id[Event], ToolInvoke] = events.collect {
      case ti: ToolInvoke => ti._id -> ti
    }.toMap

    // Tool-role Messages, ToolResults, AND CapabilityResults all count
    // as paired results — FrameBuilder renders any of them as a
    // ToolResult frame.
    val pairedInvokeIds: Set[Id[Event]] = events.iterator.flatMap {
      case m: Message if m.role == MessageRole.Tool => m.origin
      case tr: ToolResults                          => tr.origin
      case cr: CapabilityResults                    => cr.origin
      case _                                        => None
    }.toSet

    val danglingInvokes: List[ToolInvoke] =
      allInvokes.values.toList.filterNot(ti => pairedInvokeIds.contains(ti._id))

    val knownEventIds: Set[Id[Event]] = events.iterator.map(_._id).toSet
    val orphanResults: List[Event] = events.collect {
      case m: Message if m.role == MessageRole.Tool && m.origin.exists(o => !knownEventIds.contains(o)) => m: Event
      case tr: ToolResults       if tr.origin.exists(o => !knownEventIds.contains(o))                   => tr: Event
      case cr: CapabilityResults if cr.origin.exists(o => !knownEventIds.contains(o))                   => cr: Event
    }

    for
      _ <- rapid.Stream.emits(danglingInvokes).evalMap { invoke =>
             // Stamp the synthetic result +1ms after the parent so it
             // shares the parent's curator-window fate and sorts after it.
             val synth = Message(
               participantId  = invoke.participantId,
               conversationId = convId,
               topicId        = invoke.topicId,
               topicIndex     = invoke.topicIndex,
               content        = Vector(ResponseContent.Text(FailureContentTemplate)),
               role           = MessageRole.Tool,
               state          = EventState.Complete,
               timestamp      = Timestamp(invoke.timestamp.value + 1L),
               disposition    = MessageDisposition.Failure(
                                  recoverable  = false,
                                  errorContext = Some(ErrorContext(
                                    classification         = ErrorClassification.FrameworkBug,
                                    exceptionClass         = None,
                                    message                = SourceTag,
                                    stackHead              = Nil,
                                    suggestion             = Some(
                                      "Reconciled at boot from prior session corruption — no agent action needed."
                                    ),
                                    frameworkBugLikelihood = 1.0
                                  ))
                                ),
               origin         = Some(invoke._id),
               source         = Some(SourceTag)
             )
             tx.insert(synth)
           }.drain
      _ <- rapid.Stream.emits(orphanResults).evalMap(o => tx.delete(o._id)).drain
    yield Report(synthesizedFailures = danglingInvokes.size, deletedOrphans = orphanResults.size)
