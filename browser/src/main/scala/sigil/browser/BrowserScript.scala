package sigil.browser

import fabric.define.Definition
import fabric.io.JsonFormatter
import fabric.rw.*
import fabric.{Json, Obj, str}
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import rapid.{Stream, Task, Unique}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.event.{Event, Message, MessageRole}
import sigil.participant.ParticipantId
import sigil.provider.{ConversationMode, Mode}
import sigil.signal.EventState
import sigil.storage.StoredFile
import sigil.tool.model.ResponseContent
import sigil.tool.{JsonInput, Tool, ToolExample, ToolInput, ToolName}

import scala.concurrent.duration.*

/**
 * Persistable, replayable browser action sequence. Created at runtime
 * by an agent (typically via [[CreateBrowserScriptTool]]) and written
 * to `SigilDB.tools` as a [[Tool]] record so future turns can
 * `find_capability` and invoke it like any other named tool.
 *
 *   - **`steps`** is the literal action sequence. Each step's string
 *     fields may include `${arg}` placeholders that resolve against
 *     the invocation's typed args, and `${outputs.<name>}` placeholders
 *     for binding earlier `Extract(name, ...)` results.
 *   - **`parameters`** is the JSON-Schema-equivalent [[Definition]]
 *     for the args; surfaced as `inputDefinition` so the provider
 *     grammar-constrains the agent's call.
 *   - **`cookieJarId`** optionally references a [[CookieJar]] —
 *     replay loads its cookies via `BrowserSigil.cookieJarFor` so
 *     logged-in state persists across replays. Apps managing
 *     per-user jars set this on creation and the framework handles
 *     the resume.
 *   - **`space`** follows the framework single-assignment rule.
 *
 * Replay semantics: the script's `execute` runs every step in order
 * through the same [[BrowserController]] the primitive tools use, so
 * the persistent browser session and live `BrowserStateDelta` flow
 * are identical to a hand-driven session. The terminal tool result
 * is a single `Message(role = Tool)` whose JSON payload aggregates
 * the per-step outputs.
 */
case class BrowserScript(name: ToolName,
                         description: String,
                         parameters: Definition,
                         steps: List[BrowserStep],
                         override val space: SpaceId,
                         cookieJarId: Option[Id[CookieJar]] = None,
                         override val keywords: Set[String] = Set.empty,
                         override val modes: Set[Id[Mode]] = Set(Id("web-browser")),
                         override val examples: List[ToolExample] = Nil,
                         override val createdBy: Option[ParticipantId] = None,
                         override val created: Timestamp = Timestamp(Nowish()),
                         override val modified: Timestamp = Timestamp(Nowish()),
                         override val _id: Id[Tool] = Id(Unique())) extends Tool derives RW {

  override val inputRW: RW[JsonInput] = summon[RW[JsonInput]]

  override def inputDefinition: Definition = parameters

  override def execute(input: ToolInput, context: TurnContext): Stream[Event] = {
    val args = input match {
      case j: JsonInput => j.json
      case other        => summon[RW[ToolInput]].read(other)
    }
    Stream.force(BrowserScript.runSteps(this, args, context))
  }
}

object BrowserScript {

  /** Run every step in order against the conversation's
    * [[BrowserController]]. Returns a single tool-result Message
    * aggregating per-step outputs. */
  private[browser] def runSteps(script: BrowserScript,
                                args: Json,
                                context: TurnContext): Task[Stream[Event]] = context.sigil match {
    case bs: BrowserSigil =>
      val outputs = scala.collection.mutable.LinkedHashMap.empty[String, String]
      val log     = scala.collection.mutable.ListBuffer.empty[Json]

      def resolve(template: String): String =
        Resolver.resolve(template, args, outputs.toMap)

      def stepTask(controller: BrowserController, step: BrowserStep): Task[Unit] = step match {
        case BrowserStep.Navigate(url, wait) =>
          val resolved = resolve(url)
          controller.run { browser =>
            for {
              _     <- browser.navigate(resolved)
              _     <- browser.waitForLoaded(timeout = wait.seconds)
              title <- browser.title
            } yield {
              log += fabric.obj("step" -> str("navigate"), "url" -> str(resolved), "title" -> str(title))
              ()
            }
          }.flatMap { _ =>
            bs.publish(BrowserStateDelta(
              target         = controller.stateId,
              conversationId = context.conversation.id,
              url            = Some(resolved),
              loading        = Some(false)
            )).unit
          }

        case BrowserStep.Click(selector) =>
          val s = resolve(selector)
          controller.run(_(robobrowser.select.Selector(s)).click).map { _ =>
            log += fabric.obj("step" -> str("click"), "selector" -> str(s))
            ()
          }

        case BrowserStep.Type(selector, value, clearFirst) =>
          val s = resolve(selector)
          val v = resolve(value)
          controller.run { browser =>
            if (clearFirst) browser(robobrowser.select.Selector(s)).value(fabric.Str(v))
            else browser.eval(
              s"""const els = document.querySelectorAll("$s");
                 |els.forEach(el => {
                 |  el.value = (el.value || '') + ${JsonFormatter.Compact(fabric.Str(v))};
                 |  el.dispatchEvent(new Event('input', { bubbles: true }));
                 |});""".stripMargin
            ).unit
          }.map { _ =>
            log += fabric.obj("step" -> str("type"), "selector" -> str(s))
            ()
          }

        case BrowserStep.Scroll(direction, amount) =>
          val js = (direction.toLowerCase, amount.toLowerCase) match {
            case (_, "top")    => "window.scrollTo(0, 0);"
            case (_, "bottom") => "window.scrollTo(0, document.body.scrollHeight);"
            case ("up", _)     => "window.scrollBy(0, -window.innerHeight);"
            case _             => "window.scrollBy(0, window.innerHeight);"
          }
          controller.run(_.eval(js).unit).map { _ =>
            log += fabric.obj("step" -> str("scroll"), "direction" -> str(direction), "amount" -> str(amount))
            ()
          }

        case BrowserStep.Screenshot(waitSeconds) =>
          // Capture, persist via storage, and emit a delta with the
          // new screenshotFileId. The Message-with-Image is NOT emitted
          // here — script replay focuses on machine output; an app
          // that wants the image inline can chain a second tool call.
          for {
            bytes  <- controller.run { browser =>
                        Task.defer {
                          val tmp = java.nio.file.Files.createTempFile("sigil-script-shot-", ".png")
                          browser.screenshotAs(tmp, afterLoadDelay = Some(waitSeconds.seconds)).map { _ =>
                            val read = java.nio.file.Files.readAllBytes(tmp)
                            try java.nio.file.Files.deleteIfExists(tmp) catch { case _: Throwable => () }
                            read
                          }
                        }
                      }
            stored <- bs.storeBytes(sigil.GlobalSpace, bytes, "image/png",
                        metadata = Map("kind" -> "browser-screenshot", "scriptName" -> script.name.value))
            _      <- bs.publish(BrowserStateDelta(
                        target           = controller.stateId,
                        conversationId   = context.conversation.id,
                        screenshotFileId = Some(stored._id)
                      ))
          } yield {
            log += fabric.obj("step" -> str("screenshot"), "fileId" -> str(stored._id.value))
            ()
          }

        case BrowserStep.SaveHtml(name) =>
          // Persist the current page's normalized HTML and bind the
          // resulting file id under outputs.<name>. Mirrors the
          // BrowserSaveHtmlTool implementation so script replay and
          // hand-driven sessions produce identical artifacts.
          import org.jsoup.Jsoup
          for {
            capture <- controller.run { browser =>
                         browser(robobrowser.select.Selector("html")).outerHTML
                           .map(_.headOption.getOrElse(""))
                           .map(html => (html, browser.url()))
                       }
            (rawHtml, currentUrl) = capture
            doc        = Jsoup.parse(rawHtml)
            normalized = doc.outerHtml()
            bytes      = normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            stored    <- bs.storeBytes(GlobalSpace, bytes, "text/html",
                          metadata = Map(
                            "kind" -> "browser-html",
                            "conversationId" -> context.conversation.id.value,
                            "url" -> currentUrl,
                            "scriptName" -> script.name.value
                          ))
            _         <- bs.publish(BrowserStateDelta(
                          target         = controller.stateId,
                          conversationId = context.conversation.id,
                          htmlFileId     = Some(stored._id)
                        ))
          } yield {
            outputs.put(name, stored._id.value)
            log += fabric.obj(
              "step"       -> str("saveHtml"),
              "name"       -> str(name),
              "htmlFileId" -> str(stored._id.value),
              "url"        -> str(currentUrl)
            )
            ()
          }

        case BrowserStep.XPathQuery(htmlRef, xpath, name, maxResults, includeOuterHtml) =>
          import org.jsoup.Jsoup
          import scala.jdk.CollectionConverters.*
          val resolvedRef = resolve(htmlRef)
          val resolvedXp  = resolve(xpath)
          bs.fetchStoredFile(lightdb.id.Id[StoredFile](resolvedRef), context.chain).map {
            case None =>
              val payload = fabric.obj(
                "error" -> str(s"htmlFileId '$resolvedRef' not found or not authorized")
              )
              outputs.put(name, JsonFormatter.Compact(payload))
              log += fabric.obj("step" -> str("xpathQuery"), "name" -> str(name), "error" -> str("not_found"))
              ()
            case Some((_, fileBytes)) =>
              val html = new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8)
              val doc  = Jsoup.parse(html)
              val all  = doc.selectXpath(resolvedXp).iterator().asScala.toList
              val limited = all.take(maxResults)
              val matches = limited.map { el =>
                val attrs = el.attributes().iterator().asScala.toList.map(a => a.getKey -> str(a.getValue))
                val base = List(
                  "xpath"      -> str(sigil.browser.tool.BrowserHtmlOverview.xpathOf(el)),
                  "tag"        -> str(el.tagName()),
                  "text"       -> str(sigil.browser.tool.BrowserHtmlOverview.squish(el.text()).take(500)),
                  "attributes" -> fabric.obj(attrs*)
                )
                val full =
                  if (includeOuterHtml) base :+ ("outerHtml" -> str(el.outerHtml().take(4000)))
                  else base
                fabric.obj(full*)
              }
              val payload = fabric.obj(
                "htmlFileId" -> str(resolvedRef),
                "xpath"      -> str(resolvedXp),
                "matches"    -> fabric.arr(matches*),
                "totalCount" -> fabric.num(all.size),
                "returned"   -> fabric.num(limited.size)
              )
              outputs.put(name, JsonFormatter.Compact(payload))
              log += fabric.obj(
                "step"       -> str("xpathQuery"),
                "name"       -> str(name),
                "totalCount" -> fabric.num(all.size),
                "returned"   -> fabric.num(limited.size)
              )
              ()
          }

        case BrowserStep.TextSearch(htmlRef, query, name, contextChars, maxResults, caseSensitive) =>
          import org.jsoup.Jsoup
          val resolvedRef   = resolve(htmlRef)
          val resolvedQuery = resolve(query)
          bs.fetchStoredFile(lightdb.id.Id[StoredFile](resolvedRef), context.chain).map {
            case None =>
              val payload = fabric.obj(
                "error" -> str(s"htmlFileId '$resolvedRef' not found or not authorized")
              )
              outputs.put(name, JsonFormatter.Compact(payload))
              log += fabric.obj("step" -> str("textSearch"), "name" -> str(name), "error" -> str("not_found"))
              ()
            case Some((_, fileBytes)) =>
              val html = new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8)
              val doc  = Jsoup.parse(html)
              val body = Option(doc.body()).getOrElse(doc).text()
              val haystack    = body
              val haystackLU  = if (caseSensitive) haystack else haystack.toLowerCase
              val needleLU    = if (caseSensitive) resolvedQuery else resolvedQuery.toLowerCase
              val positions   = scala.collection.mutable.ListBuffer.empty[Int]
              if (needleLU.nonEmpty) {
                var idx = haystackLU.indexOf(needleLU)
                while (idx >= 0) {
                  positions += idx
                  idx = haystackLU.indexOf(needleLU, idx + needleLU.length)
                }
              }
              val limited = positions.take(maxResults).toList
              val matches = limited.map { pos =>
                val matchText = haystack.substring(pos, math.min(pos + resolvedQuery.length, haystack.length))
                val before    = haystack.substring(math.max(0, pos - contextChars), pos)
                val afterEnd  = math.min(haystack.length, pos + matchText.length + contextChars)
                val after     = haystack.substring(math.min(haystack.length, pos + matchText.length), afterEnd)
                fabric.obj(
                  "position"      -> fabric.num(pos),
                  "contextBefore" -> str(before),
                  "matchText"     -> str(matchText),
                  "contextAfter"  -> str(after)
                )
              }
              val payload = fabric.obj(
                "htmlFileId" -> str(resolvedRef),
                "query"      -> str(resolvedQuery),
                "matches"    -> fabric.arr(matches*),
                "totalCount" -> fabric.num(positions.size),
                "returned"   -> fabric.num(limited.size)
              )
              outputs.put(name, JsonFormatter.Compact(payload))
              log += fabric.obj(
                "step"       -> str("textSearch"),
                "name"       -> str(name),
                "totalCount" -> fabric.num(positions.size),
                "returned"   -> fabric.num(limited.size)
              )
              ()
          }

        case BrowserStep.WaitForCondition(jsExpression, timeoutSeconds) =>
          val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L)
          def loop: Task[Boolean] =
            controller.run(_.eval(s"return Boolean($jsExpression);").map(_.apply("result")("value").asBoolean))
              .flatMap {
                case true  => Task.pure(true)
                case false if System.currentTimeMillis() > deadline => Task.pure(false)
                case false => Task.sleep(250.millis).flatMap(_ => loop)
              }
          loop.map { settled =>
            log += fabric.obj("step" -> str("waitForCondition"), "settled" -> fabric.bool(settled))
            ()
          }
      }

      // Resolve controller, run every step, build the final message.
      bs.browserController(context.conversation.id, context.caller, context.chain).flatMap { controller =>
        Task.sequence(script.steps.map(stepTask(controller, _))).map { _ =>
          val payload = fabric.obj(
            "script"  -> str(script.name.value),
            "steps"   -> fabric.arr(log.toList*),
            "outputs" -> fabric.obj(outputs.map { case (k, v) => k -> str(v) }.toList*)
          )
          Stream.emit[Event](Message(
            participantId  = context.caller,
            conversationId = context.conversation.id,
            topicId        = context.conversation.currentTopicId,
            content        = Vector(ResponseContent.Text(JsonFormatter.Compact(payload))),
            state          = EventState.Complete,
            role           = MessageRole.Tool
          ))
        }
      }

    case _ =>
      Task.pure(Stream.emit[Event](Message(
        participantId  = context.caller,
        conversationId = context.conversation.id,
        topicId        = context.conversation.currentTopicId,
        content        = Vector(ResponseContent.Text(
          "Sigil instance does not mix in BrowserSigil; cannot execute browser script."
        )),
        state          = EventState.Complete,
        role           = MessageRole.Tool
      )))
  }

  /** Simple `${arg.path}` and `${outputs.name}` template resolver.
    * Supports dotted paths into JSON object args; missing paths
    * resolve to the empty string (script authors should declare
    * `parameters` so missing args are caught at provider grammar
    * time, not silently). */
  object Resolver {
    private val Pattern = """\$\{([^}]+)\}""".r

    def resolve(template: String, args: Json, outputs: Map[String, String]): String =
      Pattern.replaceAllIn(template, m => {
        val path = m.group(1)
        java.util.regex.Matcher.quoteReplacement(lookup(path, args, outputs))
      })

    private def lookup(path: String, args: Json, outputs: Map[String, String]): String = {
      val parts = path.split('.').toList
      parts match {
        case "outputs" :: name :: Nil => outputs.getOrElse(name, "")
        case keys =>
          val v = keys.foldLeft(args) { case (j, key) =>
            j match {
              case o: Obj => o.value.getOrElse(key, fabric.Null)
              case _      => fabric.Null
            }
          }
          v match {
            case s: fabric.Str => s.value
            case fabric.Null   => ""
            case other         => JsonFormatter.Compact(other)
          }
      }
    }
  }
}
