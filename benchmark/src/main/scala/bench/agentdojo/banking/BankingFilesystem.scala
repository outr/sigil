package bench.agentdojo.banking

import fabric.rw.*

/**
 * Read-only filesystem mock — a flat name → contents map. Powers the
 * `read_file` tool for tasks that consume bills, landlord notices,
 * etc. Mirrors AgentDojo's `Filesystem`.
 */
final case class BankingFilesystem(files: Map[String, String]) derives RW
