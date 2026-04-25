package sigil.participant

import lightdb.id.Id
import sigil.PolyType

trait ParticipantId extends Id[Participant]

object ParticipantId extends PolyType[ParticipantId]
