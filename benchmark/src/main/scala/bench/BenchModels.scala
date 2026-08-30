package bench

import lightdb.id.Id
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}

/**
 * Model records for benchmark runners. Sigil requires every runtime
 * model to be registered (`UnregisteredModelException` otherwise), and
 * a local llama.cpp server publishes no catalog metadata, so the
 * benchmarks hand-build the record.
 */
object BenchModels {

  /** A llama.cpp-served local model. `contextLength` is the server's
    * configured window; pricing is zero — local inference has no
    * per-token cost, which is exactly why the corpus runs here. */
  def llamaCpp(modelId: Id[Model], name: String, contextLength: Long = 32768L): Model = Model(
    canonicalSlug = s"llamacpp/$name",
    huggingFaceId = "",
    name = name,
    displayName = Some(name),
    description = "",
    contextLength = contextLength,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "Unknown",
      instructType = None
    ),
    pricing = ModelPricing(BigDecimal(0), BigDecimal(0), None, None),
    topProvider = ModelTopProvider(Some(contextLength), None, false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    defaultParameters = ModelDefaultParameters(),
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(""),
    created = lightdb.time.Timestamp(),
    modified = lightdb.time.Timestamp(),
    _id = modelId
  )
}
