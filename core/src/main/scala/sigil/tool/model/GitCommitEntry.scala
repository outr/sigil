package sigil.tool.model

import fabric.rw.*

/**
 * One commit row in a [[GitLogOutput]]. `sha` is the full commit
 * hash; `author` the author name; `date` the author date in ISO-8601
 * form; `subject` the first line of the message; `body` the rest of
 * the message when requested via `includeBody` and non-empty.
 */
case class GitCommitEntry(sha: String,
                          author: String,
                          date: String,
                          subject: String,
                          body: Option[String] = None) derives RW
