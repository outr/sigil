package sigil

import fabric.rw.*

import java.nio.file.Path

case class Config(dbPath: Path = Path.of("db/sigil")) derives RW