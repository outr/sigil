package sigil.script

/**
 * Thread-local hand-off used by [[ScalaScriptExecutor]] to inject
 * arbitrary host values into the Scala 3 REPL's scope.
 *
 * The REPL only has access to types it can resolve at compile time;
 * it can't be handed an opaque `Any` directly. We work around this
 * by:
 *   1. Stashing the value here.
 *   2. Having the REPL evaluate
 *      `val foo = ScriptValueHolder.store[FooType]`, which the
 *      compiler accepts because the cast is to a named type.
 *   3. Forcing the binding eagerly so the ThreadLocal can be cleared
 *      before the next binding is staged.
 *
 * Thread-local rather than a global var so multiple executors don't
 * race; lazy `val` initialization on the REPL side ensures the value
 * is read before the next `bind` overwrites the holder.
 */
object ScriptValueHolder {
  private val tl: ThreadLocal[Any] = new ThreadLocal[Any]()

  def store: Any = tl.get()

  def store_=(value: Any): Unit = tl.set(value)

  def clear(): Unit = tl.remove()
}
