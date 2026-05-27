# ❌ #293 — RequestModelCatalog / ModelCatalogSnapshot signal pair

**Where:** `sigil/signal/`. Consumer filed from Voidcraft
`backend/.../server/ProvidersEndpoint.scala` and
`app/lib/widget/panel/providers_browser_panel.dart`.

**What's wrong:** Sigil ships `ModelRegistry` (`sigil.cache`) with a global
in-memory map of `Model` rows, kept fresh by `OpenRouter.refreshModels`. The
agent uses `cache.all` / `cache.find` / `cache.findTolerant` on the hot path.
But UIs that want to show "every model the backend knows about" have to
expose a REST endpoint that wraps `cache.all` — Voidcraft just did this in
`ProvidersEndpoint`. The data is global; the wrapping is pure duplication.

**Suggested fix:** Mirror the existing signal pattern. Add:

```scala
// sigil/signal/RequestModelCatalog.scala
case class RequestModelCatalog(provider: Option[String] = None,
                               modality: Option[String] = None,
                               query: Option[String] = None) extends Notice derives RW

// sigil/signal/ModelCatalogSnapshot.scala
case class ModelCatalogSnapshot(models: List[Model]) extends Notice derives RW
```

Default arm:

```scala
case r: sigil.signal.RequestModelCatalog =>
  val filtered = cache.all
    .filter(m => r.provider.forall(_.equalsIgnoreCase(m.provider)))
    .filter(m => r.modality.forall(md =>
      m.architecture.modality.equalsIgnoreCase(md) ||
        m.architecture.inputModalities.exists(_.equalsIgnoreCase(md))))
    .filter(m => r.query.forall(q =>
      m.name.toLowerCase.contains(q.toLowerCase) ||
        m._id.value.toLowerCase.contains(q.toLowerCase)))
  publishTo(fromViewer, ModelCatalogSnapshot(filtered))
```

Once shipped, Voidcraft deletes `ProvidersEndpoint.scala` +
`voidcraft_providers_client.dart`; the panel becomes a tome-controller
observer.
