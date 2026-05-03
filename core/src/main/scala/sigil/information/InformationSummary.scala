package sigil.information

import fabric.rw.*
import lightdb.id.Id

/**
 * A lightweight reference to content the LLM can look up by id. Entries
 * surface in the provider context as a brief catalog ("Referenced
 * content:") of `id` + `summary` lines; the full typed record is fetched
 * on demand through [[sigil.Sigil.getInformation]] when the LLM cites the
 * id via [[sigil.tool.util.LookupTool]].
 *
 * `informationType` pins the entry to a specific registered
 * [[Information]] subtype so renderers and resolvers can route without
 * resolving the full record first. The valid set of names is derived at
 * runtime from [[Information]]'s poly registration — sigil itself takes
 * no position on what kinds of content exist.
 *
 * @param id              typed identifier the LLM uses to look up content
 * @param informationType classifier naming a registered [[Information]] subtype
 * @param summary         1-2 line description shown in the catalog
 */
case class InformationSummary(id: Id[Information], informationType: PolyName[Information], summary: String) derives RW
