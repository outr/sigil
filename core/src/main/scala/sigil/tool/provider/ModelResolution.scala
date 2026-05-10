package sigil.tool.provider

import lightdb.id.Id
import rapid.Task
import sigil.TurnContext
import sigil.db.Model

/**
 * Result of resolving a user-supplied model reference (literal id,
 * friendly alias, or bare model name) against the running Sigil's
 * registry.
 *
 *   - [[Resolved]] — the input mapped to a real registered model.
 *     `via` records which resolution rule matched (used by tool
 *     replies to explain "I interpreted 'local' as 'llamacpp/qwen3.5-9b'").
 *   - [[Unresolved]] — the input doesn't match any rule. Carries the
 *     pre-built guidance message tools surface verbatim so the agent
 *     can read alternatives without re-querying.
 */
sealed trait ModelResolutionResult

object ModelResolutionResult {

  /** Concrete resolved id plus the rule that matched. */
  final case class Resolved(modelId: Id[Model], via: Resolution) extends ModelResolutionResult

  /** No rule matched; `guidance` lists what the caller can try next. */
  final case class Unresolved(input: String, guidance: String) extends ModelResolutionResult

  /** Discriminator for which rule matched — surfaced in tool
    * replies so the agent / user can see how the input was
    * interpreted. */
  enum Resolution {
    case Alias       // mapped via ModelAlias
    case ExactId     // exact registry hit
    case BareModel   // <provider>/<input> lookup against the registry
  }
}

/**
 * Reference resolver — the canonical input pipeline for
 * [[PinModelTool]] / [[SwitchModelTool]].
 *
 * Order:
 *   1. Empty / blank → unresolved.
 *   2. Alias resolver via [[ModelAlias]] (covers `"current"`,
 *      `"local"`, `"openai"`, …).
 *   3. Strict registry hit on the literal id.
 *   4. Bare-model fallback — when the input has no `/` and the
 *      registry contains exactly one model whose `model` field
 *      matches case-insensitively, take that id (one-of-many
 *      fallback prevents ambiguous matches like `"4o"` from
 *      silently picking).
 *   5. Otherwise → unresolved with a helpful message listing
 *      legal aliases plus a few candidate registry ids.
 */
object ModelResolution {

  import ModelResolutionResult.*

  def resolve(input: String, ctx: TurnContext): Task[ModelResolutionResult] = {
    val raw = input.trim
    if (raw.isEmpty)
      Task.pure(Unresolved(input,
        "switch_model / pin_model requires a model id, alias, or saved-strategy label."))
    else {
      ModelAlias.resolve(raw, ctx).flatMap {
        case Some(id) => Task.pure(Resolved(id, Resolution.Alias))
        case None =>
          // Step 2 — strict id match on the registry, including the
          // bare-form fallback already implemented by `findTolerant`.
          val cache = ctx.sigil.cache
          cache.findTolerant(Id[Model](raw.toLowerCase)) match {
            case Some(m) => Task.pure(Resolved(m._id, Resolution.ExactId))
            case None =>
              // Step 3 — only when the input has no provider prefix,
              // try `<provider>/<input>` matches across the registry
              // (case-insensitive, exact bare-model match).
              val isBare = !raw.contains("/")
              val candidates: List[Model] =
                if (!isBare) Nil
                else cache.find(provider = None, model = Some(raw))
              candidates match {
                case List(single) => Task.pure(Resolved(single._id, Resolution.BareModel))
                case Nil          => Task.pure(Unresolved(input, refusalMessage(input, ctx)))
                case multiple     =>
                  Task.pure(Unresolved(input,
                    s"'$input' matches multiple models — please disambiguate by full id:\n" +
                      multiple.take(8).map(m => s"  - ${m._id.value}").mkString("\n")))
              }
          }
      }
    }
  }

  private def refusalMessage(input: String, ctx: TurnContext): String = {
    val cache  = ctx.sigil.cache
    val all    = cache.all
    val sample = all.sortBy(_._id.value).take(8).map(_._id.value).mkString(", ")
    val moreCount = (all.size - 8).max(0)
    val moreSuffix = if (moreCount > 0) s"  ...and $moreCount more (call list_models for the full set).\n" else ""
    val aliases = ModelAlias.allAliasNames.mkString(", ")
    s"Cannot resolve '$input' to a known model.\n\n" +
      s"Aliases: $aliases\n" +
      s"Registered models${if (all.isEmpty) " (none yet — provider catalogs may still be loading)" else ""}: $sample\n" +
      moreSuffix +
      "Try `current_model` to see what's currently active, or `list_models` to browse the registry."
  }
}
