package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.core.RespondTool

/**
 * Locks in the `topicLabel` guidance hint that tells the agent to
 * prefer including a named context (project, repo, document, ticket
 * id) in the label whenever one is visible in the prompt. Without
 * this hint, agents produce generic labels like "Refactor cleanup"
 * across every project, leaving the user's history view ambiguous.
 */
class RespondTopicLabelDescriptionSpec extends AnyWordSpec with Matchers {

  "RespondTool.description" should {
    "encourage including a named context in the topic label" in {
      RespondTool.description should include("named context")
    }
  }
}
