package sigil.tooling.types

import fabric.rw.*

/**
 * Foldable region in a document. `kind` is the LSP-defined kind
 * ("region", "comment", "imports") when the server provides it;
 * defaults to "region" for unlabeled folds. Lines are 1-based.
 */
case class LspFoldingRangeItem(kind: String, startLine: Int, endLine: Int) derives RW
