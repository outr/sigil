package sigil.workflow

import sigil.conversation.ActiveSkillSlot
import sigil.provider.{Mode, ToolPolicy}
import sigil.tool.ToolName

/**
 * Workflow-authoring mode. Activates the [[WorkflowBuilderSkill]]
 * in the system prompt and curates the tool roster to the workflow
 * management surface — agents in this mode have everything they
 * need to compose, edit, and run typed workflows, plus `respond`
 * for talking to the user and `find_capability` for discovering
 * other tools when the workflow's steps need to call them.
 *
 * Apps switch in via `change_mode` (when registered alongside the
 * other modes) or pin per-agent via `Conversation.currentMode`.
 */
case object WorkflowBuilderMode extends Mode {
  override val name: String = "workflow-builder"

  override val description: String =
    "Composing, editing, and running typed workflows on top of Sigil's workflow runtime. " +
      "Use the workflow_* tools to author + manage; everything is typed end-to-end."

  override val skill: Option[ActiveSkillSlot] = Some(ActiveSkillSlot(
    name = "WorkflowBuilder",
    content = WorkflowBuilderSkill.text
  ))

  /** `Active` — keeps the framework essentials (respond, no_response,
    * stop, find_capability, the response-shape tools) AND adds the
    * workflow management tools on top. The agent can both author
    * workflows and reply conversationally to the user. */
  override val tools: ToolPolicy = ToolPolicy.Active(List(
    ToolName("create_workflow"),
    ToolName("update_workflow"),
    ToolName("delete_workflow"),
    ToolName("list_workflows"),
    ToolName("get_workflow"),
    ToolName("run_workflow"),
    ToolName("cancel_workflow"),
    ToolName("resume_workflow"),
    ToolName("register_trigger"),
    ToolName("unregister_trigger"),
    ToolName("list_triggers")
  ))
}
