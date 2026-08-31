package spec

import lightdb.store.CollectionManager
import lightdb.upgrade.DatabaseUpgrade
import sigil.db.SigilDB
import sigil.mcp.McpCollections

import java.nio.file.Path

class TestMcpDB(directory: Option[Path],
                storeManager: CollectionManager,
                upgrades: List[DatabaseUpgrade] = Nil)
  extends SigilDB(directory, storeManager, upgrades) with McpCollections

object TestMcpSigil extends TestMcpSigilBase
