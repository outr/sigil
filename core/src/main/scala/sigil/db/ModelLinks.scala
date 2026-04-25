package sigil.db

import fabric.rw.*

/**
 * Related API URLs for a model.
 *
 * @param details Relative API path to the model's detailed endpoint/provider listing.
 */
case class ModelLinks(details: String) derives RW
