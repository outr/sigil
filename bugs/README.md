# Bugs

One file per open bug. Tracking bugs found in Sigil, Spice, Fabric, and
related libraries while building downstream consumers on top of Sigil.

## Convention

- Each open bug lives in its own file: `bugs/<number>-<slug>.md`. The
  slug is short, kebab-case, and meaningful — e.g.
  `42-participant-lifecycle-signals.md`.
- Adding a bug = creating a file. Closing a bug = deleting the file.
  Both work without touching shared text, so multiple consumers (and
  multiple agents) can file or close in parallel without merge conflicts.
- **Numbering preserves history.** Pick the next number after the highest
  used in `git log` (including pruned entries). Don't reuse numbers.
- **Status legend** (in each bug file's title): ⚠️ workaround in place ·
  ❌ open · ✅ fixed (and about to be pruned).

## Bug file shape

```markdown
# ❌ #N — <one-line title>

**Where:** <file paths with line refs>

**What's wrong:** <observation, why it matters, links to caller code>

**Suggested fix:** <concrete proposal, code if it helps>
```

Keep entries focused: one bug per file. If a bug spawns follow-ups, file
those as separate numbered bugs and cross-link.
