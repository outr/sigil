package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.event.{ErrorClassification, ErrorContext}

import java.nio.charset.MalformedInputException
import java.nio.file.{AccessDeniedException, NoSuchFileException}
import java.net.{ConnectException, SocketTimeoutException, UnknownHostException}

/**
 * Coverage for `ErrorContext.classify`'s exception-bucket logic.
 * The framework auto-classifies common Throwables into the
 * `ErrorClassification` enum so the agent can pick a response
 * shape (retry / fix args / report to user / file as feedback)
 * without parsing exception strings.
 */
class ErrorContextClassifySpec extends AnyWordSpec with Matchers {

  "ErrorContext.classify" should {

    "classify MalformedInputException as FrameworkBug with high likelihood" in {
      val ec = ErrorContext.classify(new MalformedInputException(1))
      ec.classification shouldBe ErrorClassification.FrameworkBug
      ec.frameworkBugLikelihood should be >= 0.85
      ec.exceptionClass.exists(_.contains("MalformedInputException")) shouldBe true
      ec.suggestion shouldBe defined
    }

    "classify NoSuchElementException as FrameworkBug" in {
      val ec = ErrorContext.classify(new NoSuchElementException("missing key"))
      ec.classification shouldBe ErrorClassification.FrameworkBug
      ec.frameworkBugLikelihood should be >= 0.8
    }

    "classify NoSuchFileException as UserInputError (no framework-bug suspicion)" in {
      val ec = ErrorContext.classify(new NoSuchFileException("/no/such/path"))
      ec.classification shouldBe ErrorClassification.UserInputError
      ec.frameworkBugLikelihood shouldBe 0.0
    }

    "classify AccessDeniedException as UserInputError with a permission hint" in {
      val ec = ErrorContext.classify(new AccessDeniedException("/protected"))
      ec.classification shouldBe ErrorClassification.UserInputError
      ec.suggestion shouldBe defined
    }

    "classify network timeout / connection errors as TransientError" in {
      val ec1 = ErrorContext.classify(new SocketTimeoutException("read timed out"))
      val ec2 = ErrorContext.classify(new ConnectException("refused"))
      val ec3 = ErrorContext.classify(new UnknownHostException("nope.invalid"))
      ec1.classification shouldBe ErrorClassification.TransientError
      ec2.classification shouldBe ErrorClassification.TransientError
      ec3.classification shouldBe ErrorClassification.TransientError
      ec1.suggestion shouldBe defined
    }

    "classify OutOfMemoryError as ResourceExhausted" in {
      val ec = ErrorContext.classify(new OutOfMemoryError("Java heap space"))
      ec.classification shouldBe ErrorClassification.ResourceExhausted
    }

    "classify unrecognised exceptions as Unknown" in {
      val ec = ErrorContext.classify(new RuntimeException("something weird"))
      ec.classification shouldBe ErrorClassification.Unknown
      ec.exceptionClass.exists(_.endsWith("RuntimeException")) shouldBe true
    }

    "capture the top stack frames" in {
      val ec = ErrorContext.classify(new RuntimeException("boom"))
      ec.stackHead should not be empty
      ec.stackHead.size should be <= 8
    }
  }
}
