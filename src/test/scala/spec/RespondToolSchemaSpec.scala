package spec

import fabric.*
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.DefinitionToSchema
import sigil.tool.RespondTool
import sigil.tool.model.{RespondInput, ResponseContent}

class RespondToolSchemaSpec extends AnyWordSpec with Matchers {
  private val schema: Json = DefinitionToSchema(RespondTool.schema.input)

  "RespondTool input schema" should {
    "be a strict object" in {
      schema("type").asString shouldBe "object"
      schema("additionalProperties").asBoolean shouldBe false
    }
    "mark content as required and title as optional" in {
      val required = schema("required").asVector.map(_.asString).toSet
      required should contain("content")
      required should not contain "title"
    }
    "declare content as an array with oneOf items" in {
      val content = schema("properties")("content")
      content("type").asString shouldBe "array"
      val items = content("items")
      items.asObj.value.keySet should contain("oneOf")
    }
    "emit a discriminated oneOf for ResponseContent variants" in {
      val branches = schema("properties")("content")("items")("oneOf").asVector
      // Branch count matches ResponseContent cases
      branches.size shouldBe 8
      branches.foreach { branch =>
        branch("type").asString shouldBe "object"
        branch("additionalProperties").asBoolean shouldBe false
        val typeProp = branch("properties")("type")
        typeProp.asObj.value.keySet should contain("const")
        branch("required").asVector.map(_.asString) should contain("type")
      }
    }
    "carry correct const discriminator values" in {
      val branches = schema("properties")("content")("items")("oneOf").asVector
      val consts = branches.map(b => b("properties")("type")("const").asString).toSet
      consts shouldBe Set("Text", "Code", "Table", "Diff", "ItemList", "Link", "Citation", "Markdown")
    }
    "require variant-specific fields (Text.text required)" in {
      val textBranch = branchByConst("Text")
      val required = textBranch("required").asVector.map(_.asString).toSet
      required should contain("text")
      required should contain("type")
    }
    "mark optional fields optional (Code.language not required)" in {
      val codeBranch = branchByConst("Code")
      val required = codeBranch("required").asVector.map(_.asString).toSet
      required should contain("code")
      required should contain("type")
      required should not contain "language"
      // but it should still appear in properties
      codeBranch("properties").asObj.value.keySet should contain("language")
    }
    "include all required fields for complex variants (Table)" in {
      val tableBranch = branchByConst("Table")
      val required = tableBranch("required").asVector.map(_.asString).toSet
      required shouldBe Set("type", "headers", "rows")
    }
    "handle Citation with multiple optional fields" in {
      val citationBranch = branchByConst("Citation")
      val required = citationBranch("required").asVector.map(_.asString).toSet
      required shouldBe Set("type", "source")
      val props = citationBranch("properties").asObj.value.keySet
      props should contain allOf ("source", "excerpt", "url", "type")
    }
  }

  private def branchByConst(name: String): Json =
    schema("properties")("content")("items")("oneOf").asVector
      .find(b => b("properties")("type")("const").asString == name)
      .getOrElse(fail(s"No branch for variant $name"))
}
