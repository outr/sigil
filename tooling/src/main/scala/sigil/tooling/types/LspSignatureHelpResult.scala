package sigil.tooling.types

import fabric.rw.*

/**
 * Function-call signature help at a position. `signatures` is empty
 * when the server has nothing to offer (cursor not at a call site).
 * `activeSignature` is the index of the highlighted overload;
 * `activeParameter` is `-1` when no parameter is active (cursor
 * outside parens, or signature has no parameters).
 */
case class LspSignatureHelpResult(signatures: List[LspSignature],
                                  activeSignature: Int,
                                  activeParameter: Int)
  derives RW
