package sigil.participant

import fabric.rw.PolyType
import lightdb.id.Id

trait ParticipantId extends Id[Participant]

object ParticipantId extends PolyType[ParticipantId]()(using scala.reflect.ClassTag(classOf[ParticipantId]))
