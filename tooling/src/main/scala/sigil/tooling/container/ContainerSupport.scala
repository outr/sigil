package sigil.tooling.container

import fabric.{Json, Obj, NumInt, NumDec, Bool, Str}
import fabric.io.JsonFormatter
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.Sigil
import sigil.tool.ToolContext
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.tool.output.ToolOutputNode

/**
 * Shared persistence helpers for the container family. Producer
 * tools call [[persistItems]] to materialise a `List[Json]` into a
 * fresh [[ToolOutputNode]] container under a synthetic `callId`;
 * consumer tools call [[readItems]] to resolve an `itemsId` back
 * to its persisted rows.
 *
 * The `itemsId` returned is a `Id[ToolOutputNode]` whose `value`
 * is the synthetic `callId` shared by every row in the container.
 * `next_page(itemsId.value)` reads top-level rows of the container;
 * `query_tool_output(itemsId.value, …)` queries across the container.
 */
object ContainerSupport {

  /** Persist `items` into [[ToolOutputNode]] under a fresh
    * synthetic callId. Returns `(itemsId, itemCount)`. */
  def persistItems(host: Sigil,
                   conversationId: Id[Conversation],
                   items: List[Json]): Task[(Id[ToolOutputNode], Int)] = {
    val containerId = Event.id()
    val rows = items.zipWithIndex.map { case (item, ordinal) =>
      ToolOutputNode(
        conversationId = conversationId,
        callId         = containerId,
        referenceId    = containerId.value,
        level          = 0,
        ordinal        = ordinal,
        hasChildren    = false,
        payload        = item,
        created        = Timestamp(),
        modified       = Timestamp()
      )
    }
    val itemsId: Id[ToolOutputNode] = Id[ToolOutputNode](containerId.value)
    if (rows.isEmpty) Task.pure((itemsId, 0))
    else host.withDB(_.toolOutputs.transaction { tx =>
      Task.sequence(rows.map(r => tx.upsert(r))).map(_ => (itemsId, rows.size))
    })
  }

  /** Resolve an `itemsId` back to its persisted top-level rows in
    * input order. Filters by conversation scope so callers can't
    * read containers from other conversations. */
  def readItems(host: Sigil,
                conversationId: Id[Conversation],
                itemsId: Id[ToolOutputNode]): Task[List[ToolOutputNode]] =
    host.withDB(_.toolOutputs.transaction(_.list)).map { all =>
      all.toList
        .filter(_.conversationId == conversationId)
        .filter(r => r.callId.value == itemsId.value || r.referenceId == itemsId.value || r._id.value == itemsId.value)
        .sortBy(r => (r.level, r.ordinal))
    }

  /** Resolve + project an `itemsId` to a `List[Json]` for consumer
    * tools that just need the payloads. Honors optional `itemsAt`
    * (level filter; default 0 = top-level) and `itemsLimit` (hard
    * cap). */
  def resolveItems(ctx: ToolContext,
                   itemsId: Id[ToolOutputNode],
                   itemsAt: Option[Int],
                   itemsLimit: Option[Int]): Task[List[Json]] = {
    val level = itemsAt.getOrElse(0)
    readItems(ctx.sigil, ctx.conversation.id, itemsId).map { rows =>
      val filtered = rows.filter(_.level == level).sortBy(_.ordinal).map(_.payload)
      itemsLimit match {
        case Some(n) if n >= 0 => filtered.take(n)
        case _                 => filtered
      }
    }
  }

  /** Stringify a fabric Json value for substring / regex matching
    * (compact JSON for objects / arrays; raw value for scalars). */
  def stringify(j: Json): String = j match {
    case Str(s, _)    => s
    case NumInt(n, _) => n.toString
    case NumDec(d, _) => d.toString
    case Bool(b, _)   => b.toString
    case fabric.Null  => "null"
    case other        => JsonFormatter.Compact(other)
  }

  /** Resolve a dotted path into a fabric Json value. Returns
    * `None` when any intermediate step misses (object key not
    * present, array index out of range). */
  def resolvePath(json: Json, path: String): Option[Json] = {
    val parts = path.split("\\.").toList.filter(_.nonEmpty)
    parts.foldLeft(Option(json)) {
      case (Some(o: Obj), key) => o.value.get(key)
      case (Some(_), _)        => None
      case (None, _)           => None
    }
  }

  /** Truthiness check for a resolved [[ContainerPredicate.JsonPath]]
    * value with no `equals`. */
  def isTruthy(j: Json): Boolean = j match {
    case fabric.Null     => false
    case Bool(b, _)      => b
    case Str(s, _)       => s.nonEmpty
    case NumInt(n, _)    => n != 0
    case NumDec(d, _)    => d.compare(BigDecimal(0)) != 0
    case o: Obj          => o.value.nonEmpty
    case a: fabric.Arr   => a.value.nonEmpty
    case null            => false
  }

  /** Evaluate a [[ContainerPredicate]] against a payload Json. */
  def evaluate(predicate: ContainerPredicate, payload: Json): Boolean = predicate match {
    case ContainerPredicate.Contains(text)            =>
      stringify(payload).contains(text)
    case ContainerPredicate.JsonPath(path, equalsOpt) =>
      resolvePath(payload, path) match {
        case None        => false
        case Some(value) =>
          equalsOpt match {
            case Some(expected) => value == expected
            case None           => isTruthy(value)
          }
      }
    case ContainerPredicate.RegexMatch(field, pattern) =>
      val r = scala.util.Try(pattern.r).toOption
      r.exists { rx =>
        resolvePath(payload, field) match {
          case None        => false
          case Some(value) => rx.findFirstIn(stringify(value)).isDefined
        }
      }
  }
}
