package sigil.tool

/**
 * A tool call whose input was decoded through the owning tool's own
 * [[WireSurface]] — the tool and its typed input are packed together at
 * decode time, so downstream dispatch never re-resolves by name and never
 * casts: `input` is path-dependently typed as THIS tool's `Input`.
 */
sealed trait DecodedCall {
  val tool: Tool
  val input: tool.Input

  /** Typed read-back for a caller holding a specific tool. The
    * reference-identity check makes the cast sound: `t eq tool` implies
    * `t.Input` is exactly this call's input type. A same-named but
    * distinct instance (tools constructed per call, e.g. the topic
    * classifier) round-trips the input through `t`'s own surface —
    * typed by decode, never by cast. */
  def inputFor(t: Tool): Option[t.Input] =
    if (t eq tool) Some(input.asInstanceOf[t.Input])
    else if (t.name.value == tool.name.value) t.wireSurface.decode(tool.inputRW.read(input)).toOption
    else None
}

object DecodedCall {
  def apply(t: Tool)(i: t.Input): DecodedCall { val tool: t.type } =
    new DecodedCall {
      val tool: t.type = t
      val input: tool.Input = i
    }
}
