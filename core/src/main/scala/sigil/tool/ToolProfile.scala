package sigil.tool

/**
 * A tool's behavioral capabilities, each carrying its obligations at
 * the type level: declaring [[Effect.Destructive]] forces the wire
 * warning, declaring [[Execution.Detachable]] forces a progress
 * story, declaring a consent gate forces the consent prompt.
 * Everything downstream — read cache, wire prefix, checkpoint
 * classification, gating — derives from this one value instead of
 * consulting parallel flags.
 */
final case class ToolProfile(effect: Effect,
                             execution: Execution = Execution.Inline,
                             gates: ToolGates = ToolGates.none,
                             output: OutputBounds = OutputBounds.FrameworkBounded)
