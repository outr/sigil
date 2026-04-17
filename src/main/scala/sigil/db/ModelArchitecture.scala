package sigil.db

import fabric.rw.*

/**
 * Modality and tokenizer metadata for a model.
 *
 * @param modality         Combined I/O modality descriptor, e.g. `text->text` or `text+image->text`.
 * @param inputModalities  Accepted input modalities (`text`, `image`, `audio`, ...).
 * @param outputModalities Produced output modalities.
 * @param tokenizer        Tokenizer family name (e.g. `Claude`, `GPT`, `Llama3`).
 * @param instructType     Prompt template family when the model uses a named chat/instruct format
 *                         (`llama3`, `chatml`, `gemma`, `mistral`, ...); `None` for models with
 *                         no named template.
 */
case class ModelArchitecture(modality: String,
                             inputModalities: List[String],
                             outputModalities: List[String],
                             tokenizer: String,
                             instructType: Option[String]) derives RW
