# ❌ #298 — Per-session viewer rebind (multi-device sync as framework primitive)

**Where:** `sigil/transport/WsServer.scala`, `sigil/transport/SessionBridge.scala`,
`sigil/transport/SignalTransport.scala`. Filed from Voidcraft bug #4 after
attempting to wire `sage.transport.UserBroadcastHub` and discovering the
underlying primitive is missing.

**What's wrong:** Multi-device session sync (one user, multiple connected
devices; state changes on one device fan out to the others) is a generic
multi-tenant agent-system need, not a per-app concern. Sigil's signal-routing
primitive already supports it architecturally — `publishTo(viewer, signal)`
reaches every session bound to that viewer. So if all of a user's devices
share `viewer = user/X`, a single `publishTo(user/X, signal)` already fans out
correctly.

The missing piece is **per-session viewer binding**:

- `WsServer` constructor takes one fixed `viewer: ParticipantId`. Every
  session that attaches to that server uses that viewer.
- `SessionBridge.attach` takes `viewer: ParticipantId` at attach time and
  the binding doesn't change after attach.
- Apps therefore can't bind a session to its authenticated user's
  participant id after login completes — every session stays bound to
  whatever the pre-auth viewer was (typically a system-level placeholder).

Sage's `sage.transport.UserBroadcastHub` is a workaround for this — it
maintains a parallel `user → set of session ids` map and the app does its
own fan-out at the application layer. That's fine as a transitional
pattern but it shouldn't be the long-term answer:

- It duplicates state Sigil's signal transport could index natively.
- The app has to remember to register/unregister at every auth boundary.
- The fan-out path goes through the app's own dispatch instead of through
  Sigil's filter+transform pipeline, so per-viewer signal redaction
  (`canSee`, `applyViewerTransforms`) gets bypassed unless the app
  re-implements it.

**Suggested fix:**

Option A — viewer resolver callback (simpler, covers the common case):

```scala
final class WsServer[Info: RW](
  sigil: Sigil,
  resolveViewer: (Session, Info) => Task[ParticipantId],   // new
  port: Int,
  // existing: resolveChannel, onSessionStart, config, ...
)
```

The resolver runs once per session at handshake time. For Voidcraft, `Info`
already carries an optional auth token; the resolver looks the token up
(via `UserSession.getByToken`) and returns either `user/<userId>` for an
authed connection or a stable per-device pre-auth viewer (so even
unauthenticated devices for the same browser session collapse). This
covers ~90% of real-world cases because most apps establish identity at
WS handshake.

Option B — dynamic rebind (covers the rest):

Add `SessionBridge.rebindViewer(newViewer): Task[Unit]` (or a method on
`Session` if that's where the public seam should live). Calling it
detaches the old viewer's `signalsFor` stream and re-attaches under the
new one. Apps invoke this from their `AuthNotice` handler when login
completes mid-session.

The two are complementary: Option A is the default path; Option B is
escape-hatch for apps that swap identity without reconnecting.

**Once shipped:**

- Sage's `UserBroadcastHub` can be retired (or kept as an optional
  optimization for apps that want a `sessionsFor(userId)` query that
  doesn't require iterating the signal transport's internals).
- Voidcraft's bug #4 closes — no broadcast hub wiring needed; auth-
  complete just binds the session to `user/<userId>` and Sigil's existing
  `publishTo` does the rest.
- Every downstream app gets multi-device sync for free.

**Cross-link:** retires Voidcraft bug #4; supersedes the deferral in
Sage's `sage.transport.UserBroadcastHub` docstring.
