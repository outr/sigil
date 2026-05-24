package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.{ToolOutput, TextToolOutput}
import sigil.tool.model.{WriteFileOutput, EditFileOutput, ReadFileOutput, GitStatusOutput, GitBranchEntry, GitStatusEntry}

class ToolOutputRoundTripSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  "ToolOutput polymorphic RW" should {
    "round-trip a top-level case-class output" in {
      val out: ToolOutput = ReadFileOutput("hello", totalLines = 1, linesRead = 1)
      val json = summon[RW[ToolOutput]].read(out)
      val back = summon[RW[ToolOutput]].write(json)
      back shouldBe out
    }

    "round-trip a TextToolOutput (case object companion)" in {
      val out: ToolOutput = TextToolOutput("hi")
      val json = summon[RW[ToolOutput]].read(out)
      summon[RW[ToolOutput]].write(json) shouldBe out
    }

    "round-trip WriteFileOutput.Success — a case CLASS variant of an enum" in {
      val out: ToolOutput = WriteFileOutput.Success(bytesWritten = 11L, hash = None)
      val json = summon[RW[ToolOutput]].read(out)
      summon[RW[ToolOutput]].write(json) shouldBe out
    }

    "round-trip EditFileOutput.Success — a sibling case-class variant whose leaf name collides with WriteFileOutput.Success" in {
      val out: ToolOutput = EditFileOutput.Success(replacements = 2, hash = Some("abc"))
      val json = summon[RW[ToolOutput]].read(out)
      summon[RW[ToolOutput]].write(json) shouldBe out
    }

    "round-trip WriteFileOutput.NotFound — a case OBJECT variant" in {
      val out: ToolOutput = WriteFileOutput.NotFound
      val json = summon[RW[ToolOutput]].read(out)
      summon[RW[ToolOutput]].write(json) shouldBe out
    }

    "round-trip ToolOutput.Pending — case object inside the ToolOutput companion" in {
      val out: ToolOutput = ToolOutput.Pending
      val json = summon[RW[ToolOutput]].read(out)
      summon[RW[ToolOutput]].write(json) shouldBe out
    }
  }
}
