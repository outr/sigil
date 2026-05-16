package sigil.provider.debug

import fabric.io.JsonParser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import spice.http.client.intercept.Interceptor
import spice.http.{HttpMethod, HttpRequest}
import spice.net.{URL, url}

import java.nio.file.{Files, Path}

/**
 * Coverage for sigil bug #194 — per-chunk SSE diagnostics. Verifies
 * that [[FileChunkLogger]] receives one `chunk` line per SSE data
 * line plus one `stream-end` summary line at termination, with
 * timing fields populated and the largest inter-chunk gap correctly
 * identified.
 */
class ChunkLoggerSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private def freshLogPath: Path = {
    val p = Files.createTempFile("sigil-chunk-log-spec-", ".jsonl")
    p.toFile.deleteOnExit()
    Files.write(p, Array.emptyByteArray) // truncate
    p
  }

  private def readLines(path: Path): List[String] = {
    import scala.jdk.CollectionConverters.*
    Files.readAllLines(path).asScala.toList.filter(_.nonEmpty)
  }

  private val fakeRequest: HttpRequest = HttpRequest(
    method = HttpMethod.Post,
    url    = url"https://test.example/api/v1/chat/completions"
  )

  "FileChunkLogger + StreamWireInterceptor.attach" should {

    "emit one chunk line per SSE data line plus a stream-end summary on clean termination" in {
      val logPath = freshLogPath
      val logger  = FileChunkLogger(logPath)

      // Fake SSE wire-line stream: 3 data chunks plus an empty
      // separator line (which must NOT count toward the chunk index).
      val lines = Stream.force[String](Task {
        Stream.emits(List(
          """data: {"choices":[{"delta":{"content":"a"}}]}""",
          "",
          """data: {"choices":[{"delta":{"content":"b"}}]}""",
          """data: {"choices":[{"delta":{"content":"c"}}]}"""
        ))
      })

      StreamWireInterceptor.attach(lines, Interceptor.empty, fakeRequest, logger) { _ =>
        Stream.empty
      }.drain.map { _ =>
        val written = readLines(logPath)
        val parsed  = written.map(JsonParser.apply)
        val chunks  = parsed.filter(_.get("kind").exists(_.asString == "chunk"))
        val ends    = parsed.filter(_.get("kind").exists(_.asString == "stream-end"))
        chunks should have size 3
        ends should have size 1
        chunks.zipWithIndex.foreach { case (c, i) =>
          c("chunkIndex").asInt shouldBe i
          c("byteSize").asInt should be > 0
          c("elapsedSinceRequestMs").asInt should be >= 0
          c("preview").asString should startWith ("data:")
        }
        ends.head("totalChunks").asInt shouldBe 3
        ends.head("terminatedBy").asString shouldBe "clean"
      }
    }

    "report the largest inter-chunk gap correctly on stream-end" in {
      val logPath = freshLogPath
      val logger  = FileChunkLogger(logPath)

      // Three chunks: small gap, large gap, small gap. The
      // longestGapAtChunkIndex should point at chunk 2 (the chunk
      // that arrived after the large gap).
      val lines = Stream.force[String](Task {
        Stream.emits(List(
          """data: {"a":1}""",
          """data: {"a":2}""",
          """data: {"a":3}"""
        ))
      }).evalMap { line =>
        // Inject a gap before the third chunk.
        if (line.endsWith("3}")) Task.sleep(scala.concurrent.duration.DurationInt(200).millis).map(_ => line)
        else Task.pure(line)
      }

      StreamWireInterceptor.attach(lines, Interceptor.empty, fakeRequest, logger) { _ =>
        Stream.empty
      }.drain.map { _ =>
        val parsed = readLines(logPath).map(JsonParser.apply)
        val end    = parsed.find(_.get("kind").exists(_.asString == "stream-end")).get
        end("longestGapAtChunkIndex").asInt shouldBe 2
        end("longestInterChunkGapMs").asInt should be >= 150
      }
    }

    "respect previewBytes = 0 by emitting empty preview strings" in {
      val logPath = freshLogPath
      val logger  = FileChunkLogger(logPath, previewBytes = 0)

      val lines = Stream.force[String](Task {
        Stream.emit("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}")
      })

      StreamWireInterceptor.attach(lines, Interceptor.empty, fakeRequest, logger) { _ =>
        Stream.empty
      }.drain.map { _ =>
        val chunks = readLines(logPath).map(JsonParser.apply)
          .filter(_.get("kind").exists(_.asString == "chunk"))
        chunks should have size 1
        chunks.head("preview").asString shouldBe ""
      }
    }

    "mark terminatedBy = error when the line stream errors mid-flight" in {
      val logPath = freshLogPath
      val logger  = FileChunkLogger(logPath)

      // Mimic production: a single line-stream whose pull task errors
      // mid-flight (analogous to spice's HTTP streamLines raising on
      // network drop / read timeout). The error propagates through the
      // OUTER pull's stepTask, which `onErrorFinalize` captures into
      // the errorRef so the streamEnd summary can report it.
      val lines = Stream.emits(List("data: {}", "BOOM")).evalMap { line =>
        if (line == "BOOM") Task.error[String](new RuntimeException("boom"))
        else Task.pure(line)
      }

      StreamWireInterceptor.attach(lines, Interceptor.empty, fakeRequest, logger) { _ =>
        Stream.empty
      }.drain.handleError(_ => Task.unit).map { _ =>
        val written = readLines(logPath)
        withClue(s"log lines: $written: ") {
          val parsed = written.map(JsonParser.apply)
          val end    = parsed.find(_.get("kind").exists(_.asString == "stream-end")).get
          end("terminatedBy").asString shouldBe "error"
        }
      }
    }

    "default to NoOp — no log file written when chunkLogger is omitted" in {
      val logPath = freshLogPath
      // Pre-write a marker line so we can confirm nothing else gets appended.
      Files.writeString(logPath, "marker\n")

      val lines = Stream.force[String](Task {
        Stream.emit("""data: {"a":1}""")
      })

      // No chunkLogger arg → defaults to NoOp.
      StreamWireInterceptor.attach(lines, Interceptor.empty, fakeRequest) { _ =>
        Stream.empty
      }.drain.map { _ =>
        readLines(logPath) shouldBe List("marker")
      }
    }
  }
}
