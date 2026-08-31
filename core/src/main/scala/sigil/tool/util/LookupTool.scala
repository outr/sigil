package sigil.tool.util

import fabric.rw.*
import fabric.{Json, Obj, Str}
import fabric.io.JsonFormatter
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.GlobalSpace
import sigil.tool.ToolContext
import sigil.conversation.ContextMemory
import sigil.information.Information
import sigil.information.Information.given
import sigil.skill.Skill
import sigil.tool.discovery.CapabilityType
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolIO, ToolName, ToolProfile, ToolSpec}
import sigil.tool.model.{LookupChunk, LookupInput, LookupOutput}

/**
 * Generic retrieval tool. Resolves any retrievable
 * [[sigil.tool.discovery.CapabilityMatch]] back to its full record:
 *   - `Memory`      → full [[ContextMemory]] (looked up by `key`,
 *                     falling back to `_id` for direct hits)
 *   - `Information` → full [[Information]] record
 *   - `Skill`       → full [[Skill]] record by name (the skill's
 *                     `description` + `content` markdown body, plus
 *                     `modes` / `keywords` metadata so the agent can
 *                     decide whether to `activate_skill` it)
 *
 * `Tool` and `Mode` capability types are not retrievable — tools are
 * called via their name in the tool roster; modes are entered via
 * `change_mode`. Looking those up returns a `NotRetrievable` result
 * rather than silently doing nothing.
 *
 * Emits a typed [[LookupOutput]] enum — `Found(payload)` carries the
 * matched record's JSON for the caller to deserialize against
 * whichever shape matches `capabilityType`.
 */
case object LookupTool extends Tool {
  type Input = LookupInput
  type Output = LookupOutput
  val io: ToolIO[LookupInput, LookupOutput] = ToolIO.derived[LookupInput, LookupOutput]
  override val name = ToolName("lookup")
  override val description =
    """Resolve a capability match (from a capability search) to its full record. Use this when
      |you need the details behind a memory's summary, the body of a referenced Information
      |record, or any other discovered capability with stored content.
      |
      |- `capabilityType` — what kind of record to fetch (`Memory`, `Information`, `Skill`).
      |- `name` — the identifier the match surfaced (memory key, information id-as-string,
      |  skill name).
      |- `offset` — for a LARGE record, read it in windows: the result carries a `chunk`
      |  block; if `chunk.nextOffset` is set, call lookup again with `offset = nextOffset`
      |  to read the next part, until `nextOffset` is null.
      |
      |Tools and modes are not retrievable — call tools directly; switch modes via `change_mode`.
      |Returns `Found(capabilityType, name, payload, chunk?)`, `NotFound(capabilityType, name)`, or
      |`NotRetrievable(capabilityType, name, hint)`.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("lookup", "fetch", "retrieve", "resolve", "details", "full", "expand"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: LookupInput, context: ToolContext): Task[LookupOutput] = {
    val typeName = input.capabilityType.toString
    val resolved = input.capabilityType match {
      case CapabilityType.Memory => resolveMemory(input.name, typeName, context)
      case CapabilityType.Information => resolveInformation(input.name, typeName, context)
      case CapabilityType.Skill => resolveSkill(input.name, typeName, context)
      case CapabilityType.Tool =>
        Task.pure(LookupOutput.NotRetrievable(
          typeName,
          input.name,
          s"tools are not retrievable — call '${input.name}' directly."))
      case CapabilityType.Mode =>
        Task.pure(LookupOutput.NotRetrievable(
          typeName,
          input.name,
          s"modes are not retrievable — call change_mode(\"${input.name}\") to enter it."))
    }
    // Sigil #389 — `lookup` is retrieval, not generation: a large record
    // (e.g. a saved HTML page) can't fit the inline cap and there's nothing
    // to "narrow". Window the record's dominant text field so it pages in
    // chunks instead of being stubbed by the generic overflow path.
    resolved.map {
      case LookupOutput.Found(ct, name, payload, _) =>
        val (windowed, chunk) = windowPayload(payload, input.offset, context.sigil.inlineContentThreshold)
        LookupOutput.Found(ct, name, windowed, chunk)
      case other => other
    }
  }

  /**
   * Sigil #389 — window the largest string field of a Found payload (the
   * record's content) so a large record reads in pages. Returns the payload
   * untouched (and no chunk) when the whole record fits the inline cap and
   * the caller didn't page in; otherwise replaces that field with the
   * `[offset, offset+budget)` slice and a [[LookupChunk]] pointing at the
   * next offset. Budget is sized so the windowed result stays under the cap,
   * so the generic overflow path never stubs it.
   */
  private[util] def windowPayload(payload: Json, offset: Option[Int], threshold: Long): (Json, Option[LookupChunk]) =
    payload match {
      case o: Obj =>
        val strings = o.value.iterator.collect { case (k, s: Str) => (k, s.value) }.toList
        if (strings.isEmpty) (payload, None)
        else {
          val (field, full) = strings.maxBy(_._2.length)
          val total = full.length
          // Budget = inline cap minus the rendered size of everything EXCEPT
          // this field, minus headroom for the Found envelope + chunk block.
          val blanked = Obj(o.value.updated(field, Str("")))
          val baseLen = JsonFormatter.Compact(blanked).length
          val budget = math.max(2048, (threshold - baseLen - 2048).toInt)
          val start = math.max(0, offset.getOrElse(0)).min(total)
          if (offset.forall(_ <= 0) && total <= budget) (payload, None)
          else {
            val end = math.min(total, start + budget)
            val windowed = Obj(o.value.updated(field, Str(full.substring(start, end))))
            val next = if (end < total) Some(end) else None
            (windowed, Some(LookupChunk(field = field, offset = start, returned = end - start, total = total, nextOffset = next)))
          }
        }
      case _ => (payload, None)
    }

  /**
   * Resolve a memory by `key`, falling back to `_id`.
   *
   * Scoped to the caller's accessible spaces plus
   * [[sigil.GlobalSpace]] — a memory key is a stable slot name
   * (`user.email`, `project.deploy_target`), so the SAME key routinely
   * exists in every tenant's space. An unscoped `key ===` lookup
   * returns whichever row the index happens to hold first, which for
   * a multi-tenant store is a cross-tenant disclosure, not a miss.
   *
   * Among in-scope matches the CURRENT version wins (`validUntil`
   * unset) — a key resolves to what the slot holds now, never to a
   * superseded value — and the shared recall gate
   * ([[ContextMemory.isRecallable]]) applies, so a rejected or expired
   * record reads as `NotFound` rather than being handed back in full.
   */
  private def resolveMemory(name: String, typeName: String, context: ToolContext): Task[LookupOutput] =
    context.sigil.accessibleSpaces(context.chain, context.conversation.id).flatMap { accessible =>
      val spaces = accessible + GlobalSpace
      val now = Timestamp()
      def inScope(m: ContextMemory): Boolean = spaces.contains(m.spaceId) && m.isRecallable(now)
      context.sigil.withDB { db =>
        db.memories.transaction { tx =>
          tx.query.filter(_.key === Some(name)).toList.flatMap { byKey =>
            byKey.filter(inScope).sortBy(m => -m.modified.value).headOption match {
              case Some(memory) =>
                Task.pure(LookupOutput.Found(typeName, name, summon[RW[ContextMemory]].read(memory)))
              case None =>
                tx.get(Id[ContextMemory](name)).map {
                  case Some(memory) if inScope(memory) =>
                    LookupOutput.Found(typeName, name, summon[RW[ContextMemory]].read(memory))
                  case _ =>
                    LookupOutput.NotFound(typeName, name)
                }
            }
          }
        }
      }
    }

  private def resolveInformation(name: String, typeName: String, context: ToolContext): Task[LookupOutput] =
    context.sigil.getInformation(Id[Information](name)).map {
      case Some(full) => LookupOutput.Found(typeName, name, summon[RW[Information]].read(full))
      case None => LookupOutput.NotFound(typeName, name)
    }

  private def resolveSkill(name: String, typeName: String, context: ToolContext): Task[LookupOutput] =
    context.sigil.withDB(_.skills.transaction(_.get(Id[Skill](name)))).map {
      case Some(skill) => LookupOutput.Found(typeName, name, summon[RW[Skill]].read(skill))
      case None => LookupOutput.NotFound(typeName, name)
    }
}
