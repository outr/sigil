# ❌ #409 — Dart wire codegen: a notice field named `type` clobbers the envelope discriminator

**Where:** `spice.openapi.generator.dart.DurableSocketDartGenerator` (the
generated `toJson` for any signal/notice with a field named `type`). Observed in
a downstream consumer: a `PreviewKey(`type`: String, …)` notice serialized to
`{'type': 'PreviewKey', …, 'type': type, …}`.

**What's wrong:** The generator writes the wire discriminator as
`'type': '<ClassName>'` and then writes each field with its own name as the key.
When a field is itself named `type`, the emitted Dart map literal contains the
key `'type'` twice:

```dart
Map<String, dynamic> toJson() => {'type': 'PreviewKey', /*…*/, 'type': type, /*…*/};
```

Dart map literals take the **last** value for a duplicate key, so the field
silently overwrites the discriminator. The object goes on the wire as
`{"type":"keyDown"}` instead of `{"type":"PreviewKey",…}`. On the server,
`SessionBridge.noticeOrWarnLive` can't resolve `keyDown` to any registered type
and drops it with a `RuntimeException: Type not found [keyDown]` warning — so the
whole notice class is dead on arrival, with no compile-time signal on either
side. In our case it meant keyboard forwarding into the live preview never
worked (the mouse notice, which happened to name its field `kind`, was fine).

Decoding has a matching latent hazard: `fromJson` reads `json['type']` for the
field, which would now read the discriminator value, not the field value.

**Suggested fix:** In the generator, treat `type` as reserved for the
discriminator and either (a) reject / rename a colliding field at generation
time with a clear error, or (b) emit the discriminator under a non-colliding
internal key. Minimum viable: detect a field named `type` and fail codegen with
a message pointing at the offending class, so it's caught at build time instead
of as a runtime "unknown notice" in production. The Scala side has no such
collision (Fabric derives the discriminator separately from the fields), so this
is Dart-generator-only.

**Workaround (in the consumer):** don't name a notice field `type` — renamed
`PreviewKey.`type`` → `PreviewKey.kind`, matching the sibling `PreviewInput`
mouse notice that never hit this.
