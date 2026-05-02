package sigil.script

import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.ResponseContent

import java.lang.reflect.{Constructor, Field, Method, Modifier}

/**
 * Introspect a class on the executor's classpath and return its
 * constructors, public methods, and public fields. Bug #59 — the
 * agent uses this in `script-authoring` mode after
 * [[LibraryLookupTool]] resolves a symbol to one or more FQNs and
 * the agent needs the full signature surface to call into the
 * library correctly.
 *
 * Pure Java reflection — no `-sources.jar` required. Returns Scala-
 * style formatted signatures. Doesn't yet pull `@deprecated` or
 * scaladoc; those would require Scala 3 TASTy or the `-sources.jar`
 * (see [[ReadSourceTool]]).
 */
case object ClassSignaturesTool extends TypedTool[ClassSignaturesInput](
  name = ToolName("class_signatures"),
  description =
    """Return the constructors, public methods, and public fields of a known fully-qualified class.
      |Use after `library_lookup` resolves a symbol to its FQN — e.g. lookup `HttpClient` →
      |`spice.http.client.HttpClient`, then `class_signatures("spice.http.client.HttpClient")`
      |to see what methods you can call.
      |
      |The trailing `$` for Scala objects is optional. Output is plain text formatted as
      |Scala-style method signatures.""".stripMargin,
  modes = Set(ScriptAuthoringMode.id),
  keywords = Set("class", "signature", "method", "introspect", "lookup", "api")
) {

  override protected def executeTyped(input: ClassSignaturesInput, context: TurnContext): rapid.Stream[Event] = {
    val text =
      try render(loadClass(input.fqn))
      catch {
        case _: ClassNotFoundException => s"(class not found on classpath: ${input.fqn})"
        case e: Throwable               => s"(introspection failed: ${e.getClass.getSimpleName}: ${e.getMessage})"
      }
    rapid.Stream.emit(reply(context, text))
  }

  /** Resolve `fqn` to a `Class[_]`. For Scala objects, callers may pass
    * either `Foo` or `Foo$` — try both. */
  private def loadClass(fqn: String): Class[?] =
    try Class.forName(fqn)
    catch { case _: ClassNotFoundException => Class.forName(fqn + "$") }

  private def render(cls: Class[?]): String = {
    val name = cls.getName
    val ctors = cls.getDeclaredConstructors.toList
      .filter(c => Modifier.isPublic(c.getModifiers))
      .map(formatConstructor)
    val methods = cls.getDeclaredMethods.toList
      .filter(m => Modifier.isPublic(m.getModifiers))
      .filterNot(_.isSynthetic)
      .sortBy(_.getName)
      .map(formatMethod)
    val fields = cls.getDeclaredFields.toList
      .filter(f => Modifier.isPublic(f.getModifiers))
      .filterNot(_.isSynthetic)
      .sortBy(_.getName)
      .map(formatField)

    val sb = new StringBuilder
    sb.append(s"# $name\n")
    if (ctors.nonEmpty)   sb.append("\n## Constructors\n").append(ctors.mkString("\n"))
    if (fields.nonEmpty)  sb.append("\n\n## Public fields\n").append(fields.mkString("\n"))
    if (methods.nonEmpty) sb.append("\n\n## Public methods\n").append(methods.mkString("\n"))
    if (ctors.isEmpty && fields.isEmpty && methods.isEmpty)
      sb.append("\n(no public surface — likely a hidden / synthetic / module class)")
    sb.toString
  }

  private def formatConstructor(c: Constructor[?]): String = {
    val params = c.getParameterTypes.toList.map(simple).mkString(", ")
    s"  new($params)"
  }

  private def formatMethod(m: Method): String = {
    val params = m.getParameterTypes.toList.map(simple).mkString(", ")
    val returns = simple(m.getReturnType)
    val staticTag = if (Modifier.isStatic(m.getModifiers)) "static " else ""
    s"  $staticTag${m.getName}($params): $returns"
  }

  private def formatField(f: Field): String = {
    val tpe = simple(f.getType)
    val staticTag = if (Modifier.isStatic(f.getModifiers)) "static " else ""
    s"  $staticTag${f.getName}: $tpe"
  }

  /** Drop package prefix for readability. Keep generics cosmetic for now;
    * full Scala type info would require parsing TASTy. */
  private def simple(t: Class[?]): String = {
    val n = t.getName
    val short = n.substring(n.lastIndexOf('.') + 1).replace('$', '.')
    if (t.isArray) s"Array[${simple(t.getComponentType)}]" else short
  }

  private def reply(context: TurnContext, text: String): Message =
    Message(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      content        = Vector(ResponseContent.Text(text)),
      state          = EventState.Complete,
      role           = MessageRole.Tool,
      visibility     = MessageVisibility.Agents
    )
}
