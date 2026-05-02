package sigil.viewer

import fabric.rw.PolyType

/**
 * Marker trait for app-defined per-viewer UI state payloads.
 * Mirrors the `WorkType` / `Mode` / `Tool` extensibility pattern: a
 * leaf [[PolyType]] the framework registers, apps extend by
 * registering concrete subtypes via
 * [[sigil.Sigil.viewerStatePayloadRegistrations]], and fabric's
 * polymorphic RW dispatches by simple class name on the wire.
 *
 * The Dart codegen produces a real typed class for each registered
 * subtype — clients read and write `payload as MyAppViewerState`
 * directly with no `Map<String, dynamic>` round-trip.
 *
 * Example:
 *
 * {{{
 *   case class MyViewerState(activeTab: String, panelOpen: Boolean)
 *     extends ViewerStatePayload derives RW
 *
 *   override protected def viewerStatePayloadRegistrations:
 *       List[ViewerStatePayload] =
 *     List(MyViewerState(activeTab = "chat", panelOpen = false))
 * }}}
 *
 * The framework ships no concrete subtype — it has no opinion on
 * what state apps want persisted. Apps that pull in `sigil-core`
 * without a custom subtype simply don't use this primitive.
 */
trait ViewerStatePayload

object ViewerStatePayload extends PolyType[ViewerStatePayload]()(using scala.reflect.ClassTag(classOf[ViewerStatePayload]))
