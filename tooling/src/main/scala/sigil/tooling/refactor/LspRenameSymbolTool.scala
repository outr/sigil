package sigil.tooling.refactor

import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}
import sigil.tooling.types.LspWorkspaceSymbol
import sigil.tooling.{LspManager, LspToolSupport}

import scala.jdk.CollectionConverters.*

/**
 * High-level semantic rename. Wraps the existing
 * [[sigil.tooling.LspWorkspaceSymbolsTool]] +
 * [[sigil.tooling.LspRenameTool]] dance into one tool call:
 *
 *   1. Search the workspace for symbols matching `symbolName`
 *      (exact match by default; substring when `fuzzy = true`).
 *   2. Optionally filter by `kindHint`.
 *   3. If exactly one match: extract its declaration position and
 *      run the underlying `lsp_rename`. Return [[LspRenameSymbolOutput.Renamed]].
 *   4. If multiple matches: return [[LspRenameSymbolOutput.Ambiguous]]
 *      so the agent can disambiguate.
 *   5. If zero matches: return [[LspRenameSymbolOutput.NotFound]].
 *
 * The precision LSP gives (qualified-name imports, alias renames,
 * cross-module dependency updates) comes for free because we use
 * the same underlying `lsp_rename` path; this wrapper just removes
 * the lookup dance.
 *
 * Sigil bug #212.
 */
final class LspRenameSymbolTool(val manager: LspManager)
  extends TypedOutputTool[LspRenameSymbolInput, LspRenameSymbolOutput](
    name = ToolName("lsp_rename_symbol"),
    description =
      """Rename a symbol across the workspace by name (high-level, no position required).
        |
        |Wraps the lookup + rename dance into one call:
        |  - Searches the workspace for symbols matching `symbolName` (exact match by default,
        |    substring when `fuzzy = true`).
        |  - Filters by `kindHint` when set (`"class"`, `"method"`, etc.).
        |  - On a single hit, renames via LSP across the workspace; on multiple hits returns
        |    the candidates so you can pass a `kindHint` or a more specific `symbolName`.
        |
        |Use this when you know the symbol's name but not the file / line / column. For
        |position-driven renames (you already have a cursor location), call the
        |position-based LSP rename tool directly.""".stripMargin,
    keywords = Set(
      "lsp", "rename", "refactor", "symbol", "by name", "high-level",
      "semantic", "identifier", "across project", "workspace", "change name",
      // Discoverability: surface via find-and-replace queries when the
      // intent is a semantic rename, and via navigation queries that
      // are actually rename intents in disguise.
      "find", "find symbol", "replace name", "change identifier",
      "update symbol name", "global rename", "search rename"
    ),
    examples = List(
      ToolExample(
        "Rename a class by name",
        LspRenameSymbolInput(
          languageId  = "scala",
          projectRoot = "/abs/path/myproject",
          symbolName  = "OldFooConfig",
          newName     = "FooConfig",
          kindHint    = Some("class")
        )
      )
    )
  ) with sigil.tool.DestructiveExternalTool with LspToolSupport {

  override def paginate: Boolean = false

  override protected def executeTyped(input: LspRenameSymbolInput,
                                      context: TurnContext): Task[LspRenameSymbolOutput] = {
    withSessionTyped[LspRenameSymbolOutput](
      input.languageId, input.projectRoot, context,
      onError = msg => LspRenameSymbolOutput.Failed(input.symbolName, msg)
    ) { (session, _, _) =>
      session.workspaceSymbols(input.symbolName).flatMap { hits =>
        val symbols = hits.map { h =>
          LspWorkspaceSymbol(
            kind      = Option(h.kind).map(_.toString.toLowerCase).getOrElse("unknown"),
            name      = h.name,
            container = h.containerName,
            uri       = h.uri,
            position  = h.range.map(r => sigil.tooling.types.LspPosition.fromLsp4j(r.getStart))
          )
        }.toList
        val nameMatched = symbols.filter { s =>
          if (input.fuzzy) s.name.toLowerCase.contains(input.symbolName.toLowerCase)
          else s.name == input.symbolName
        }
        val kindFiltered = input.kindHint match {
          case None         => nameMatched
          case Some(hint)   => nameMatched.filter(_.kind.equalsIgnoreCase(hint))
        }
        kindFiltered match {
          case Nil =>
            Task.pure(LspRenameSymbolOutput.NotFound(
              input.symbolName,
              if (symbols.nonEmpty && input.kindHint.isDefined)
                s"workspace has ${symbols.size} symbol(s) named '${input.symbolName}' but none match kindHint='${input.kindHint.get}'"
              else if (symbols.nonEmpty)
                s"workspace has ${symbols.size} symbol(s) named like '${input.symbolName}' but no exact match (try fuzzy=true)"
              else
                s"no symbol named '${input.symbolName}' found in workspace"
            ))
          case single :: Nil =>
            single.position match {
              case None =>
                Task.pure(LspRenameSymbolOutput.Failed(
                  input.symbolName,
                  s"symbol '${single.name}' returned by lsp_workspace_symbols without a position; can't drive rename"
                ))
              case Some(pos) =>
                val uriToPath: String =
                  if (single.uri.startsWith("file:")) new java.io.File(java.net.URI.create(single.uri)).getAbsolutePath
                  else single.uri
                // LspPosition is 1-based (IDE-style); LSP renames are
                // 0-based. Convert back to wire coordinates.
                renameAt(input, uriToPath, pos.line - 1, pos.column - 1, context)
            }
          case many =>
            Task.pure(LspRenameSymbolOutput.Ambiguous(input.symbolName, many))
        }
      }
    }
  }

  /** Drive the underlying LspRenameTool flow against the resolved
    * declaration position. Adapted from [[sigil.tooling.LspRenameTool]]
    * — we don't simply delegate because that tool's output type
    * is [[sigil.tooling.types.LspRenameResult]] and we want to
    * surface a single typed [[LspRenameSymbolOutput]] here. */
  private def renameAt(input: LspRenameSymbolInput,
                       filePath: String,
                       line: Int,
                       character: Int,
                       context: TurnContext): Task[LspRenameSymbolOutput] = {
    withOpenDocumentTyped[LspRenameSymbolOutput](
      input.languageId, filePath, context,
      onError = msg => LspRenameSymbolOutput.Failed(input.symbolName, msg)
    ) { (session, uri) =>
      session.rename(uri, line, character, input.newName).flatMap {
        case None       =>
          Task.pure(LspRenameSymbolOutput.Failed(
            input.symbolName,
            s"LSP server returned no edits for rename at $filePath:$line:$character"
          ))
        case Some(edit) =>
          val ok = sigil.tooling.PermissiveWorkspaceEditApplier.apply(edit)
          val urisChanged = (
            Option(edit.getChanges).map(_.keySet().asScala.toList).getOrElse(Nil) ++
            Option(edit.getDocumentChanges).map(_.asScala.toList.flatMap { e =>
              if (e.isLeft) List(e.getLeft.getTextDocument.getUri) else Nil
            }).getOrElse(Nil)
          ).distinct
          val notifyTask = manager.notifyFilesChanged(
            urisChanged.map { u =>
              new java.io.File(java.net.URI.create(u)).getAbsolutePath -> org.eclipse.lsp4j.FileChangeType.Changed
            }.toMap
          )
          notifyTask.map { _ =>
            if (ok)
              LspRenameSymbolOutput.Renamed(
                symbolName   = input.symbolName,
                newName      = input.newName,
                filesChanged = urisChanged.size
              )
            else
              LspRenameSymbolOutput.Failed(
                input.symbolName,
                s"PermissiveWorkspaceEditApplier reported a partial failure across ${urisChanged.size} file(s)"
              )
          }
      }
    }
  }
}
