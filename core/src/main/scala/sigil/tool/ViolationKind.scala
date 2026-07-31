package sigil.tool

/**
 * What class of decode failure a [[DecodeViolation]] reports:
 *
 *   - [[Constraint]] — a field-scoped schema-constraint failure
 *     (`pattern`, length, numeric bounds, array bounds) from
 *     [[ToolInputValidator]].
 *   - [[Structural]] — the payload failed to materialise into the
 *     typed input (type-shape mismatch, missing required field).
 */
enum ViolationKind {
  case Constraint
  case Structural
}
