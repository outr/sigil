package sigil.tool.model

import fabric.rw.*

/**
 * The HTTP method `http_request` can issue. A fixed enum so the
 * generated tool schema advertises exactly the supported set rather
 * than an open string.
 */
enum HttpRequestMethod derives RW {
  case Get
  case Post
  case Put
  case Patch
  case Delete
  case Head
  case Options
}
