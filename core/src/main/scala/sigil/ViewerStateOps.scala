package sigil

import fabric.rw.*
import rapid.Task
import sigil.participant.ParticipantId

/**
 * Viewer-state and stored-file inbound-Notice handling — the
 * per-viewer key/value state vocabulary (`RequestViewerState`,
 * `UpdateViewerState`, `DeleteViewerState`, `UpdateViewerStateDelta`)
 * and the stored-file vocabulary (`RequestStoredFileList`,
 * `RequestStoredFile`, `SaveStoredFile`).
 *
 * Mixed into [[Sigil]]; [[Sigil.handleNotice]] dispatches to
 * [[viewerStateNotices]] for any Notice this cluster handles. The
 * methods remain public (or protected, for the hooks) members of
 * `Sigil`. The self-type reaches the rest of the framework's state
 * (`withDB`, `publishTo`, `accessibleSpaces`, `storeBytes`,
 * `fetchStoredFile`).
 */
trait ViewerStateOps { this: Sigil =>

  /**
   * App-defined [[sigil.viewer.ViewerStatePayload]] subtypes — the
   * concrete UI-state shapes apps want persisted per-viewer.
   *
   * Returns a list of fabric `RW[? <: ViewerStatePayload]`. Use
   * `summon[RW[MyPayload]]` for case-class payloads — the
   * macro-derived RW carries each field's schema so the Spice Dart
   * codegen can emit a real Dart class with all fields wired up.
   * For case-object singletons, `RW.static(MySingleton)` is fine.
   *
   * Same shape as [[Sigil.eventRegistrations]] /
   * [[Sigil.noticeRegistrations]] — a registration shape that returns
   * values + folds through `RW.static` (the prior shape) silently
   * drops case-class field schema, because `RW.static(instance)` is a
   * singleton-shaped RW.
   *
   * Apps that don't use [[sigil.signal.RequestViewerState]] /
   * [[sigil.signal.UpdateViewerState]] leave the default `Nil` —
   * the primitive is opt-in. The framework ships no concrete
   * subtype; "what state to persist" is a 100% app decision.
   */
  protected def viewerStatePayloadRegistrations: List[RW[? <: sigil.viewer.ViewerStatePayload]] = Nil

  /**
   * Inbound-Notice arms for the viewer-state and stored-file
   * vocabularies. [[Sigil.handleNotice]] consults this partial
   * function for any Notice it doesn't handle directly; the Notice
   * subtypes here are disjoint from the framework-level ones so
   * dispatch order is irrelevant.
   */
  protected def viewerStateNotices: PartialFunction[(sigil.signal.Notice, ParticipantId), Task[Unit]] = {
    case (sigil.signal.RequestViewerState(scope), fromViewer) =>
      val recordId = sigil.viewer.ViewerState.idFor(fromViewer, scope)
      withDB(_.viewerStates.transaction(_.get(recordId))).flatMap { existing =>
        publishTo(fromViewer, sigil.signal.ViewerStateSnapshot(scope, existing.map(_.payload)))
      }

    case (sigil.signal.UpdateViewerState(scope, payload), fromViewer) =>
      val record = sigil.viewer.ViewerState(
        participantId = fromViewer,
        scope = scope,
        payload = payload,
        modified = lightdb.time.Timestamp(),
        _id = sigil.viewer.ViewerState.idFor(fromViewer, scope)
      )
      for {
        _ <- withDB(_.viewerStates.transaction(_.upsert(record)))
        // Broadcast to every live session for this viewer so other
        // tabs / devices converge. `publishTo` fans out via the
        // hub's per-viewer queue.
        _ <- publishTo(fromViewer, sigil.signal.ViewerStateSnapshot(scope, Some(payload)))
      } yield ()

    case (sigil.signal.DeleteViewerState(scope), fromViewer) =>
      val recordId = sigil.viewer.ViewerState.idFor(fromViewer, scope)
      for {
        _ <- withDB(_.viewerStates.transaction(_.delete(recordId).map(_ => ())))
          .handleError(_ => Task.unit)
        _ <- publishTo(fromViewer, sigil.signal.ViewerStateSnapshot(scope, None))
      } yield ()

    case (sigil.signal.UpdateViewerStateDelta(scope, patch), fromViewer) =>
      val recordId = sigil.viewer.ViewerState.idFor(fromViewer, scope)
      val payloadRW = summon[fabric.rw.RW[sigil.viewer.ViewerStatePayload]]
      withDB(_.viewerStates.transaction(_.get(recordId))).flatMap { existing =>
        val mergedPayload: sigil.viewer.ViewerStatePayload = existing match {
          case None =>
            // First delta for this scope acts like a full upsert
            // — the patch IS the initial state.
            patch
          case Some(prior) =>
            // Deep-merge the patch's non-null JSON fields onto
            // the current payload's JSON via fabric's object
            // merge, then decode back through the polytype RW.
            // Stripping nulls FIRST is what makes Option-typed
            // patches express "untouched fields stay" — fabric's
            // case-class RW emits `None` as JSON `null`, and the
            // default merge would otherwise overlay those nulls
            // onto the prior. Apps that need to clear a field to
            // None pass the full state via [[UpdateViewerState]]
            // instead.
            val priorJson = payloadRW.read(prior.payload)
            val patchJson = stripNulls(payloadRW.read(patch))
            val merged = priorJson.merge(patchJson)
            payloadRW.write(merged)
        }
        val record = sigil.viewer.ViewerState(
          participantId = fromViewer,
          scope = scope,
          payload = mergedPayload,
          modified = lightdb.time.Timestamp(),
          _id = recordId
        )
        for {
          _ <- withDB(_.viewerStates.transaction(_.upsert(record)))
          // Broadcast the delta — peers apply the same patch onto
          // their existing local state. The originating session
          // already has its merged copy; the delta is for the
          // viewer's other tabs / devices.
          _ <- publishTo(fromViewer, sigil.signal.ViewerStateDelta(scope, patch))
        } yield ()
      }

    case (sigil.signal.RequestStoredFileList(spaces), fromViewer) =>
      listStoredFiles(fromViewer, spaces).flatMap { summaries =>
        publishTo(fromViewer, sigil.signal.StoredFileListSnapshot(summaries))
      }

    case (sigil.signal.RequestStoredFile(fileId), fromViewer) =>
      fetchStoredFile(fileId, List(fromViewer)).flatMap {
        case None => Task.unit
        case Some((file, bytes)) =>
          val payload = sigil.signal.StoredFileContent(
            file = sigil.signal.StoredFileSummary.fromStoredFile(file),
            base64Data = java.util.Base64.getEncoder.encodeToString(bytes)
          )
          publishTo(fromViewer, payload)
      }

    case (sigil.signal.SaveStoredFile(_, contentType, base64Data, _, _), fromViewer) =>
      // Default: the framework can't pick a SpaceId on the agent's
      // behalf without app context, so we resolve through
      // `externalizationSpaceForViewer(fromViewer)` (defaults to
      // GlobalSpace). Apps that want per-conversation tenancy
      // override that hook OR override `handleNotice` to take the
      // conversationId on the SaveStoredFile into account.
      externalizationSpaceForViewer(fromViewer).flatMap { space =>
        val bytes = java.util.Base64.getDecoder.decode(base64Data)
        storeBytes(space, bytes, contentType).flatMap { stored =>
          publishTo(
            fromViewer,
            sigil.signal.StoredFileCreated(
              sigil.signal.StoredFileSummary.fromStoredFile(stored)
            ))
        }
      }
  }

  /**
   * Resolve the [[SpaceId]] used when a viewer pushes a
   * [[sigil.signal.SaveStoredFile]] without conversation scope.
   * Default [[GlobalSpace]] — apps tune for per-user / per-tenant.
   */
  def externalizationSpaceForViewer(viewer: ParticipantId): Task[SpaceId] =
    Task.pure(GlobalSpace)

  /**
   * Resolve the list of [[sigil.signal.StoredFileSummary]] visible
   * to a viewer, optionally filtered to a subset of spaces. Default
   * walks `SigilDB.storedFiles` and filters by
   * `accessibleSpaces(List(viewer))`.
   */
  def listStoredFiles(viewer: ParticipantId,
                      spaces: Option[Set[SpaceId]] = None,
                      categories: Option[Set[sigil.storage.StoredFileCategory]] = None,
                      includeExpired: Boolean = false): Task[List[sigil.signal.StoredFileSummary]] =
    accessibleSpaces(List(viewer)).flatMap { authorized =>
      val effective = spaces.fold(authorized)(_.intersect(authorized))
      val now = lightdb.time.Timestamp()
      withDB(_.storedFiles.transaction(_.list)).map(_.toList.collect {
        case file
            if effective.contains(file.space)
              && categories.forall(_.contains(file.category))
              && (includeExpired || !file.isExpired(now)) =>
          sigil.signal.StoredFileSummary.fromStoredFile(file)
      })
    }

  /**
   * Recursively drop fields whose value is JSON `null`. Used to
   * pre-process [[sigil.signal.UpdateViewerStateDelta]] patches —
   * fabric's case-class RW emits `None` as `null`, and the default
   * merge would otherwise overlay those nulls onto the prior
   * payload, defeating the "untouched fields stay" intent.
   * Non-object JSON values pass through unchanged.
   */
  final private def stripNulls(json: fabric.Json): fabric.Json = json match {
    case obj: fabric.Obj =>
      val kept = obj.value.iterator.collect {
        case (k, v) if v != fabric.Null => (k, stripNulls(v))
      }.toMap
      fabric.Obj(kept)
    case other => other
  }
}
