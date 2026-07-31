package sigil.tool

import fabric.*
import fabric.define.{DefType, Definition}
import fabric.rw.*

/**
 * The resolution outcome of one wire-level tool call, produced by the
 * streaming accumulator after resolving the model's tool name through the
 * request's [[ToolRoster]] and decoding the args through that tool's
 * [[WireSurface]]:
 *
 *   - [[Decoded]]    — the name resolved and the args decoded; carries the
 *                      tool + typed input packed together.
 *   - [[Unresolved]] — the name didn't resolve against the roster; the
 *                      model's args are preserved verbatim (parsed JSON, or
 *                      the raw text as a `Str` when it didn't parse).
 *   - [[Malformed]]  — the name resolved but the args failed to parse or
 *                      decode; carries every violation plus the args.
 */
enum WireCall {
  case Decoded(call: DecodedCall)
  case Unresolved(name: String, rawArgs: Json)
  case Malformed(name: String, error: DecodeError, rawArgs: Json)

  /** The wire-level tool name the model invoked. */
  def toolName: String = this match {
    case Decoded(c)         => c.tool.name.value
    case Unresolved(n, _)   => n
    case Malformed(n, _, _) => n
  }

  /** Re-attempt resolution against a roster. `Decoded` passes through;
    * an `Unresolved` whose name now resolves is decoded through that
    * tool's surface. Used where a serialized event stream (replayed
    * fixtures) re-enters a context that holds the live roster. */
  def rebind(roster: ToolRoster): WireCall = this match {
    case Decoded(_) => this
    case Unresolved(name, raw) =>
      roster.resolve(name) match {
        case Some(tool) =>
          tool.wireSurface.decode(raw) match {
            case Right(input) => Decoded(DecodedCall(tool)(input))
            case Left(error)  => Malformed(name, error, raw)
          }
        case None => this
      }
    case Malformed(_, _, _) => this
  }

  /** The decoded input widened to [[ToolInput]] — `None` unless this
    * call is `Decoded`. */
  def decodedInput: Option[ToolInput] = this match {
    case Decoded(c) => Some(c.input)
    case _          => None
  }

  /** Typed read-back for a caller holding a specific tool — `Some` only
    * when this call is that tool's, carrying its typed input. An
    * `Unresolved` matching by name (a replayed fixture) decodes through
    * the tool's surface. */
  def inputFor(t: Tool): Option[t.Input] = this match {
    case Decoded(c)                                      => c.inputFor(t)
    case Unresolved(name, raw) if name == t.name.value   => t.wireSurface.decode(raw).toOption
    case _                                               => None
  }
}

object WireCall {

  /** Convenience constructor packing a tool + typed input. */
  def decoded(t: Tool)(i: t.Input): WireCall = Decoded(DecodedCall(t)(i))

  /** Serialization is name + args JSON. The tool reference inside
    * `Decoded` cannot round-trip through JSON (it is a live object), so a
    * deserialized call comes back `Unresolved` and is restored to
    * `Decoded` by [[WireCall.rebind]] against the consuming context's
    * roster. */
  given rw: RW[WireCall] = RW.from(
    r = {
      case Decoded(call) =>
        obj(
          "type" -> str("decoded"),
          "name" -> str(call.tool.name.value),
          "args" -> call.tool.inputRW.read(call.input)
        )
      case Unresolved(name, rawArgs) =>
        obj("type" -> str("unresolved"), "name" -> str(name), "args" -> rawArgs)
      case Malformed(name, error, rawArgs) =>
        obj(
          "type" -> str("malformed"),
          "name" -> str(name),
          "args" -> rawArgs,
          "violations" -> arr(error.violations.map(v =>
            obj("path" -> arr(v.path.map(str)*), "reason" -> str(v.reason), "kind" -> str(v.kind.toString)))*)
        )
    },
    w = { json =>
      val name = json("name").asString
      val args = json("args")
      json("type").asString match {
        case "malformed" =>
          val violations = json("violations").asVector.toList.map { v =>
            val kind = v.get("kind").map(_.asString) match {
              case Some("Structural") => ViolationKind.Structural
              case _                  => ViolationKind.Constraint
            }
            DecodeViolation(v("path").asVector.toList.map(_.asString), v("reason").asString, kind)
          }
          Malformed(name, DecodeError(violations, args), args)
        case _ =>
          Unresolved(name, args)
      }
    },
    d = Definition(DefType.Json, className = Some("sigil.tool.WireCall"))
  )
}
