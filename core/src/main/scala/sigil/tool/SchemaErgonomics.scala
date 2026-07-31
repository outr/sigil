package sigil.tool

import fabric.{Arr, Json, Obj, Str}

/**
 * Schema-ergonomics rule shared by [[ToolIO]]'s construction lint and
 * the audit specs: a tool input a model reliably can't fill is a
 * framework defect, not a model failure. The specific footgun — a
 * REQUIRED field that's a `oneOf`/`anyOf` union whose variant requires
 * a *nested* field (the discriminator plus a real payload field) — was
 * unfillable on a frontier model 4/4 attempts. No didactic error
 * rescues it; the schema shape itself is wrong. Flatten to scalar args
 * for the common case; keep any rich union as an *optional* advanced
 * form.
 */
object SchemaErgonomics {

  /** The discriminator key emitted on every `oneOf` branch of a sealed
    * trait / data enum. A branch requiring only the discriminator is
    * fine (the model just emits the variant tag); requiring anything
    * beyond it is the unfillable case. */
  private val Discriminator: String = WireSurface.Discriminator

  private def requiredFields(obj: Map[String, Json]): List[String] =
    obj.get("required").collect { case Arr(items, _) => items.collect { case Str(s, _) => s }.toList }.getOrElse(Nil)

  private def unionBranches(obj: Map[String, Json]): List[Json] =
    obj.get("oneOf").orElse(obj.get("anyOf")).collect { case Arr(b, _) => b.toList }.getOrElse(Nil)

  private def branchRequiresPayload(branch: Json): Boolean = branch match {
    case Obj(bm) => requiredFields(bm).exists(_ != Discriminator)
    case _       => false
  }

  /** Walk an emitted JSON Schema; report the path of every REQUIRED
    * field whose schema is a union with a payload-requiring branch.
    * Optional unions (the model can skip them) are fine and not
    * reported. Reusable for apps that want to lint their own tools. */
  def unfillableUnionFindings(schema: Json): List[String] = {
    def walk(json: Json, path: String, isRequired: Boolean): List[String] = json match {
      case Obj(m) =>
        val here =
          if (isRequired && unionBranches(m).exists(branchRequiresPayload))
            List(s"$path — required field is a oneOf/anyOf union whose variant requires a nested field; flatten to scalar args")
          else Nil
        val props = m.get("properties").collect { case Obj(p) => p }.getOrElse(Map.empty)
        val reqHere = requiredFields(m).toSet
        val kids = props.toList.flatMap { case (k, v) => walk(v, if (path.isEmpty) k else s"$path.$k", reqHere.contains(k)) }
        val arrItem = m.get("items").toList.flatMap(v => walk(v, s"$path[]", isRequired))
        here ++ kids ++ arrItem
      case _ => Nil
    }
    walk(schema, "", isRequired = true)
  }
}
