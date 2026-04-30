package sigil.storage

import lightdb.time.Timestamp
import rapid.Task
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*

import java.net.URI

/**
 * S3-backed [[StorageProvider]]. Works with any S3-compatible
 * service (AWS S3, MinIO, Backblaze B2, etc.) — `endpoint` is the
 * service URL and `bucket` is the target bucket.
 *
 * The bucket's URL is NEVER exposed to consumers — Sigil's HTTP
 * route filter proxies bytes through the local server. This lets us
 * keep access control + signing in one place and gives the framework
 * the option to swap backends without changing consumer URLs.
 *
 * Safe-edit support: [[read]] uses the object's S3 ETag as the
 * `FileVersion.hash` (S3 returns ETags as quoted strings; we strip
 * the quotes). [[writeIfMatch]] uses the `If-Match` conditional
 * header so the CAS check happens server-side — no client lock
 * needed. ETag-based comparison is exact for non-multipart objects
 * (ETag is the MD5); for multipart uploads ETag is opaque but still
 * a stable version stamp suitable for compare-and-set.
 */
final class S3StorageProvider(endpoint: String,
                              region: String,
                              accessKey: String,
                              secretKey: String,
                              bucket: String) extends StorageProvider {

  private lazy val client: S3Client = S3Client.builder()
    .endpointOverride(URI.create(endpoint))
    .region(Region.of(region))
    .credentialsProvider(StaticCredentialsProvider.create(
      AwsBasicCredentials.create(accessKey, secretKey)
    ))
    .forcePathStyle(true)
    .build()

  override def upload(path: String, data: Array[Byte], contentType: String): Task[String] = Task {
    client.putObject(
      PutObjectRequest.builder()
        .bucket(bucket)
        .key(path)
        .contentType(contentType)
        .build(),
      RequestBody.fromBytes(data)
    )
    path
  }

  override def download(path: String): Task[Option[Array[Byte]]] = Task {
    try {
      val response = client.getObject(
        GetObjectRequest.builder().bucket(bucket).key(path).build()
      )
      Some(response.readAllBytes())
    } catch {
      case _: NoSuchKeyException => None
    }
  }

  override def delete(path: String): Task[Unit] = Task {
    client.deleteObject(
      DeleteObjectRequest.builder().bucket(bucket).key(path).build()
    )
    ()
  }

  override def exists(path: String): Task[Boolean] = Task {
    try {
      client.headObject(HeadObjectRequest.builder().bucket(bucket).key(path).build())
      true
    } catch {
      case _: NoSuchKeyException => false
    }
  }

  override def read(path: String): Task[Option[StorageContents]] = Task {
    try {
      val response = client.getObject(
        GetObjectRequest.builder().bucket(bucket).key(path).build()
      )
      val bytes = response.readAllBytes()
      val resp = response.response()
      Some(StorageContents(bytes, FileVersion(stripEtag(resp.eTag()), Timestamp(resp.lastModified().toEpochMilli))))
    } catch {
      case _: NoSuchKeyException => None
    }
  }

  override def writeIfMatch(path: String,
                            data: Array[Byte],
                            contentType: String,
                            expected: FileVersion): Task[WriteResult] = Task {
    try {
      val response = client.putObject(
        PutObjectRequest.builder()
          .bucket(bucket)
          .key(path)
          .contentType(contentType)
          .ifMatch("\"" + expected.hash + "\"")
          .build(),
        RequestBody.fromBytes(data)
      )
      WriteResult.Written(FileVersion(stripEtag(response.eTag()), Timestamp()))
    } catch {
      case e: S3Exception if e.statusCode() == 412 =>
        // PreconditionFailed — current ETag didn't match. Re-fetch
        // and surface the freshest snapshot so the caller can retry.
        try {
          val response = client.getObject(
            GetObjectRequest.builder().bucket(bucket).key(path).build()
          )
          val bytes = response.readAllBytes()
          val resp = response.response()
          WriteResult.Stale(StorageContents(bytes, FileVersion(stripEtag(resp.eTag()), Timestamp(resp.lastModified().toEpochMilli))))
        } catch {
          case _: NoSuchKeyException => WriteResult.NotFound
        }
      case _: NoSuchKeyException => WriteResult.NotFound
    }
  }

  /** S3 ETags are returned as quoted strings (`"abcdef..."`). Strip
    * the quotes so the value compares cleanly with subsequent
    * `If-Match` round-trips and with caller-supplied [[FileVersion]]
    * hashes from prior reads. */
  private def stripEtag(etag: String): String =
    if (etag == null) "" else etag.stripPrefix("\"").stripSuffix("\"")
}
