package sigil.db

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp

/**
 * Locally cached view of a model record from the upstream OpenRouter-style
 * `/models` catalog.
 *
 * @param canonicalSlug       Upstream canonical slug, typically including a version suffix.
 * @param huggingFaceId       Hugging Face repository id when the model is mirrored there; empty if none.
 * @param name                Canonical name as the upstream catalog publishes it.
 *                            Typically the model-id tail or the LLM operator's chosen label;
 *                            apps preferring a friendlier UI label should read [[displayName]]
 *                            instead and fall back to this field when `displayName` is empty.
 * @param displayName         Friendly UI label suitable for chat-list metadata / model picker.
 *                            OpenRouter populates from its `name` field (e.g. `"GPT-5.5"`);
 *                            LlamaCpp leaves `None` by default (apps wire concrete formatters
 *                            in their LlamaCpp seed when they want a friendlier label than
 *                            the raw gguf basename).
 * @param description         Long-form marketing/description text.
 * @param contextLength       Maximum combined input+output context window, in tokens.
 * @param architecture        Modality and tokenizer metadata.
 * @param pricing             Per-token / per-call pricing in USD.
 * @param topProvider         Capability details reported by the top (primary) serving provider.
 * @param perRequestLimits    Optional per-request token/rate limit reported upstream.
 * @param supportedParameters Set of request parameter names the model honors (e.g. `temperature`, `tools`).
 * @param defaultParameters   Provider-recommended default values for sampling parameters.
 * @param knowledgeCutoff     Date of the model's training-data knowledge cutoff, when published.
 * @param expirationDate      Date after which the model will be deprecated / no longer served.
 * @param links               Related API URLs for this model.
 * @param created             Upstream creation timestamp for the model record.
 * @param modified            Local cache modification timestamp; refreshed on each update.
 * @param _id                 Fully-qualified model identifier (e.g. `anthropic/claude-opus-4.7`).
 */
case class Model(canonicalSlug: String,
                 huggingFaceId: String,
                 name: String,
                 displayName: Option[String] = None,
                 description: String,
                 contextLength: Long,
                 architecture: ModelArchitecture,
                 pricing: ModelPricing,
                 topProvider: ModelTopProvider,
                 perRequestLimits: Option[Long],
                 supportedParameters: Set[String],
                 defaultParameters: ModelDefaultParameters = ModelDefaultParameters(),
                 knowledgeCutoff: Option[Timestamp],
                 expirationDate: Option[Timestamp],
                 links: ModelLinks,
                 created: Timestamp,
                 modified: Timestamp = Timestamp(),
                 _id: Id[Model])
  extends RecordDocument[Model] {
  lazy val (provider: String, model: String) = {
    val array = _id.value.split("/", 2)
    (array.head, array.last)
  }
}

object Model extends RecordDocumentModel[Model] with JsonConversion[Model] {
  implicit override def rw: RW[Model] = RW.gen

  val provider: I[String] = field.index(_.provider)
  val model: I[String] = field.index(_.model)

  def id(provider: String, model: String): Id[Model] = Id(s"${provider.toLowerCase}/${model.toLowerCase}")
}
