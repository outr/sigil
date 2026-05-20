package sigil.information

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.{GlobalSpace, SpaceId}

/**
 * The framework's default persisted [[Information]] — a flat content
 * record minted by [[sigil.conversation.compression.StandardBlockExtractor]]
 * when a frame's content crosses the size threshold, and resolved
 * back by [[sigil.Sigil.getInformation]]. Stored in
 * `SigilDB.storedInformations`.
 *
 * `space` segments records by tenancy, as with the framework's other
 * records. The LightDB id mirrors the framework-allocated
 * `Id[Information]` so a placeholder reference round-trips back here.
 */
case class StoredInformation(content: String,
                             space: SpaceId = GlobalSpace,
                             created: Timestamp = Timestamp(),
                             modified: Timestamp = Timestamp(),
                             _id: Id[StoredInformation] = StoredInformation.id())
  extends Information, RecordDocument[StoredInformation]:
  override def id: Id[Information] = Id(_id.value)

object StoredInformation extends RecordDocumentModel[StoredInformation] with JsonConversion[StoredInformation]:
  override implicit val rw: RW[StoredInformation] = RW.gen

  val space: I[String] = field.index(_.space.value)

  override def id(value: String = Unique()): Id[StoredInformation] = Id(value)

  /** `toInformation` factory for [[sigil.conversation.compression.StandardBlockExtractor]]:
    * mints a record whose `_id` shares the framework-allocated id. */
  def fromInformationId(id: Id[Information], content: String, space: SpaceId = GlobalSpace): StoredInformation =
    StoredInformation(content = content, space = space, _id = Id(id.value))
