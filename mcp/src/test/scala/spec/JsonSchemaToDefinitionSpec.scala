package spec

import fabric.*
import fabric.define.{DefType, Definition}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.JsonSchemaToDefinition

class JsonSchemaToDefinitionSpec extends AnyWordSpec with Matchers {

  "JsonSchemaToDefinition" should {
    "convert a primitive string schema" in {
      val schema = obj("type" -> str("string"))
      JsonSchemaToDefinition(schema).defType shouldBe DefType.Str
    }

    "convert primitive integer / number / boolean schemas" in {
      JsonSchemaToDefinition(obj("type" -> str("integer"))).defType shouldBe DefType.Int
      JsonSchemaToDefinition(obj("type" -> str("number"))).defType shouldBe DefType.Dec
      JsonSchemaToDefinition(obj("type" -> str("boolean"))).defType shouldBe DefType.Bool
    }

    "preserve descriptions" in {
      val schema = obj("type" -> str("string"), "description" -> str("a name"))
      JsonSchemaToDefinition(schema).description shouldBe Some("a name")
    }

    "convert an object schema with required and optional properties" in {
      val schema = obj(
        "type" -> str("object"),
        "properties" -> obj(
          "name" -> obj("type" -> str("string")),
          "age"  -> obj("type" -> str("integer"))
        ),
        "required" -> Arr(Vector(str("name")))
      )
      val defType = JsonSchemaToDefinition(schema).defType
      defType match {
        case DefType.Obj(map) =>
          map("name").defType shouldBe DefType.Str
          map("age").defType shouldBe a[DefType.Opt]
        case other => fail(s"expected Obj, got $other")
      }
    }

    "convert an array schema with primitive items" in {
      val schema = obj(
        "type"  -> str("array"),
        "items" -> obj("type" -> str("string"))
      )
      JsonSchemaToDefinition(schema).defType match {
        case DefType.Arr(inner) => inner.defType shouldBe DefType.Str
        case other              => fail(s"expected Arr, got $other")
      }
    }

    "fall back to DefType.Json for unknown / missing types" in {
      JsonSchemaToDefinition(obj()).defType shouldBe DefType.Json
      JsonSchemaToDefinition(obj("type" -> str("never-heard-of-this"))).defType shouldBe DefType.Json
    }

    "convert a string-enum schema" in {
      val schema = obj("enum" -> Arr(Vector(str("red"), str("blue"))))
      JsonSchemaToDefinition(schema).defType shouldBe a[DefType.Poly]
    }
  }
}
