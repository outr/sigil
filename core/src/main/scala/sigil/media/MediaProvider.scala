package sigil.media

import rapid.{Stream, Task}

/**
 * Pluggable media services — text-to-speech, speech-to-text, image
 * generation. Sigil core defines the trait; apps wire a concrete
 * implementation (Sage ships ElevenLabs TTS, OpenAI DALL-E image gen,
 * Whisper STT). Apps without a media wiring leave the default
 * [[NoOpMediaProvider]] in place.
 *
 * Each method is optional in the sense that a given provider may not
 * support every modality. Unsupported modalities raise a `Task.error`
 * with [[MediaProvider.UnsupportedMediaOperation]] so callers can
 * fail-fast or degrade gracefully.
 *
 * Implementations are expected to be thread-safe.
 */
trait MediaProvider {

  /**
   * Synthesize speech audio from text. Returns the audio bytes plus the
   * MIME type the bytes are encoded as (e.g. `audio/mpeg`,
   * `audio/wav`). `voiceId` is provider-specific — apps that need
   * persona-bound voices store the id on their persona record and pass
   * it through here.
   */
  def textToSpeech(text: String, voiceId: Option[String] = None): Task[MediaProvider.AudioBlob] =
    Task.error(MediaProvider.UnsupportedMediaOperation("textToSpeech"))

  /**
   * Stream-friendly TTS variant — emits chunks as they arrive from the
   * upstream service. Default falls back to the buffered
   * [[textToSpeech]] form (one big chunk) so providers that don't
   * stream can leave this alone.
   */
  def textToSpeechStream(text: String, voiceId: Option[String] = None): Stream[MediaProvider.AudioChunk] =
    Stream.force(textToSpeech(text, voiceId).map { blob =>
      Stream.emits(List(MediaProvider.AudioChunk(blob.bytes, blob.contentType, isFinal = true)))
    })

  /**
   * Transcribe audio bytes to text. `contentType` describes the input
   * (`audio/wav`, `audio/mpeg`, `audio/webm`, etc.); `language` is an
   * optional ISO-639-1 hint when the provider supports it.
   */
  def speechToText(audio: Array[Byte],
                   contentType: String,
                   language: Option[String] = None): Task[String] =
    Task.error(MediaProvider.UnsupportedMediaOperation("speechToText"))

  /**
   * Generate an image from a textual prompt. Returns the image bytes
   * plus its MIME type (`image/png`, `image/jpeg`). `size` is provider
   * specific — most TTI services accept square strings like `"1024x1024"`.
   */
  def generateImage(prompt: String,
                    size: Option[String] = None): Task[MediaProvider.ImageBlob] =
    Task.error(MediaProvider.UnsupportedMediaOperation("generateImage"))
}

object MediaProvider {

  /** Synthesized speech bytes. */
  final case class AudioBlob(bytes: Array[Byte], contentType: String)

  /** A single chunk of streamed TTS output. `isFinal = true` indicates
    * the last chunk in the stream — consumers can finalize playback /
    * download once they see it. */
  final case class AudioChunk(bytes: Array[Byte], contentType: String, isFinal: Boolean)

  /** Generated image bytes. */
  final case class ImageBlob(bytes: Array[Byte], contentType: String)

  /** Raised by methods a given provider doesn't implement. The
    * `operation` field carries the method name (`textToSpeech`,
    * `speechToText`, `generateImage`) so handlers can switch on it
    * without string matching. */
  final case class UnsupportedMediaOperation(operation: String)
    extends RuntimeException(s"MediaProvider does not support `$operation`")
}

/**
 * Default no-op media provider. Every method returns
 * [[MediaProvider.UnsupportedMediaOperation]]; apps without media
 * wiring use this so the `mediaProvider` field always resolves.
 */
object NoOpMediaProvider extends MediaProvider
