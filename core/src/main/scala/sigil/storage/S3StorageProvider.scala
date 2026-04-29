package sigil.storage

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
}
