package sigil

import fabric.rw.*

case class PostgresConfig(jdbcUrl: String,
                          username: Option[String] = None,
                          password: Option[String] = None,
                          maximumPoolSize: Option[Int] = None)
  derives RW
