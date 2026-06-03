package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.GlobalSpace
import sigil.conversation.Conversation
import sigil.signal.Signal
import sigil.storage.StoredFileCategory
import sigil.transport.{DurableFileChannel, StoredFileRef}
import spice.http.Headers
import spice.http.durable.{DurableSocket, DurableSocketConfig, InMemoryEventLog, ReceivedFile}

import java.nio.file.Files

/**
 * Coverage for the Sigil↔spice file-transfer bridge ([[DurableFileChannel]]). The chunked transport
 * itself is proven in spice's `FileTransferSpec`; here we verify the Sigil-side mapping: an inbound
 * [[ReceivedFile]] is persisted through [[sigil.Sigil.storeBytes]] with its payload metadata, and
 * downloads honor [[sigil.Sigil.fetchStoredFile]]'s space authorization.
 */
class DurableFileChannelSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private case class TestInfo(conversationId: String) derives RW

  private val socket = new DurableSocket[Id[Conversation], Signal, TestInfo](
    config = DurableSocketConfig(),
    outboundLog = new InMemoryEventLog[Id[Conversation], Signal],
    initialChannelId = Conversation.id("file-conv")
  )
  private val channel = new DurableFileChannel[Id[Conversation], TestInfo](TestSigil, socket, List(TestUser))

  private def tempWith(bytes: Array[Byte]): java.nio.file.Path = {
    val tmp = Files.createTempFile("dfc-", ".bin")
    Files.write(tmp, bytes)
    tmp
  }

  "DurableFileChannel" should {

    "persist an inbound upload through storeBytes with its payload metadata" in {
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set(GlobalSpace)))
      val data = new Array[Byte](40_000)
      new scala.util.Random(7L).nextBytes(data)
      val received = ReceivedFile(
        transferId = "t-upload",
        value = StoredFileRef(space = GlobalSpace, category = StoredFileCategory.UserAttachment, title = Some("design.zip"), language = Some("binary")),
        headers = Headers.empty.setHeader("Content-Type", "application/zip"),
        path = tempWith(data)
      )
      for {
        stored <- channel.persist(received)
        back   <- TestSigil.fetchStoredFile(stored._id, List(TestUser))
      } yield {
        stored.contentType shouldBe "application/zip"
        stored.space shouldBe GlobalSpace
        stored.metadata.get("title") shouldBe Some("design.zip")
        stored.metadata.get("language") shouldBe Some("binary")
        back.map(b => java.util.Arrays.equals(b._2, data)) shouldBe Some(true)
        // the receiver's temp spool is reclaimed after persist
        Files.exists(received.path) shouldBe false
      }
    }

    "fail a download the chain isn't authorized to read" in {
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set(GlobalSpace)))
      for {
        stored <- TestSigil.storeBytes(GlobalSpace, "secret".getBytes("UTF-8"), "text/plain")
        _ = TestSigil.setAccessibleSpaces(_ => Task.pure(Set.empty)) // revoke
        result <- channel.send(stored._id).map(_ => "sent").handleError(_ => Task.pure("denied"))
      } yield result shouldBe "denied"
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
