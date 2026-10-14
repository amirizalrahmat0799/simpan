# simpan

[![CI](https://github.com/amirizalrahmat0799/simpan/actions/workflows/ci.yml/badge.svg)](https://github.com/amirizalrahmat0799/simpan/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F)
[![license](https://img.shields.io/github/license/amirizalrahmat0799/simpan)](LICENSE)

**An S3-compatible object storage server in Java.** Point the AWS CLI, any AWS SDK, rclone or your own app at it and
it behaves like Amazon S3: buckets, objects, multipart uploads, presigned URLs, checksums, conditional writes. One
process, one data directory, no external database.

*Simpan* is Malay for "keep" or "save".

Self-hosted S3 matters more since MinIO's community edition stopped publishing binaries and Docker images (October 2025)
and moved to maintenance mode. simpan is a small, readable alternative for development, CI, home labs and
single-node deployments.

> **Status: 0.1.0-SNAPSHOT.** Single node, single tenant. Not a replacement for a replicated production cluster.

## Quick start

```bash
docker compose up -d            # S3 API on :9000, health and metrics on :9001
```

or without Docker (Java 21):

```bash
mvn package
SIMPAN_ACCESS_KEY=admin SIMPAN_SECRET_KEY=change-me-please java -jar target/simpan.jar
```

Then use it like S3:

```bash
export AWS_ACCESS_KEY_ID=admin AWS_SECRET_ACCESS_KEY=change-me-please AWS_DEFAULT_REGION=us-east-1
aws --endpoint-url http://localhost:9000 s3 mb s3://photos
aws --endpoint-url http://localhost:9000 s3 cp holiday.mp4 s3://photos/2026/
aws --endpoint-url http://localhost:9000 s3 presign s3://photos/2026/holiday.mp4 --expires-in 3600
```

From Spring Boot with the AWS SDK for Java v2:

```java
S3Client s3 = S3Client.builder()
        .endpointOverride(URI.create("http://localhost:9000"))
        .region(Region.US_EAST_1)
        .forcePathStyle(true)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("admin", "change-me-please")))
        .build();
```

## What works

| Area | Operations |
|---|---|
| Buckets | ListBuckets, CreateBucket, HeadBucket, DeleteBucket, GetBucketLocation, GetBucketVersioning, GetBucketAcl |
| Objects | PutObject, GetObject, HeadObject, DeleteObject, DeleteObjects (batch), CopyObject |
| Listing | ListObjectsV2 and ListObjects: prefix, delimiter, pagination, start-after, `encoding-type=url` |
| Multipart | Create, UploadPart, UploadPartCopy (with ranges), Complete, Abort, ListParts, ListMultipartUploads |
| Reads | Byte ranges, `partNumber` reads, If-Match / If-None-Match / If-Modified-Since / If-Unmodified-Since, response header overrides |
| Writes | Conditional writes (`If-None-Match: *` create-only, `If-Match` compare-and-swap), user metadata, content headers |
| Auth | Signature V4 (headers and presigned URLs), streaming V4 with signed chunks and signed trailers, unsigned trailers, Signature V2 (headers and presigned URLs) |
| Integrity | CRC32, CRC32C, CRC64NVME, SHA-1, SHA-256 checksums as headers or trailers, Content-MD5, `x-amz-content-sha256`; full-object and composite checksums for multipart |
| Operations | Prometheus metrics, health probes, Docker image |

Not yet: versioning, bucket policies and IAM-style permissions, object lock, lifecycle rules, server-side encryption,
tagging, static website hosting, replication, SigV4A, browser POST uploads. Requests for these get a clear
`NotImplemented` error.

## How it's verified

- **AWS's published test vectors**: the SigV4 and SigV2 examples in the S3 API reference are reproduced exactly, including the
  streaming-upload chunk signatures, the signed-trailer signature and the CRC32C trailer value.
- **Real clients in CI**, on every push:
  - boto3 (the AWS SDK for Python): 71 checks, over plain HTTP and HTTPS. HTTPS matters because that is when the
    SDK switches to `aws-chunked` bodies with trailing CRC32 checksums.
  - boto3 1.34, the version Ubuntu 24.04 ships, to keep older clients working. (This is why simpan runs on Jetty
    rather than Tomcat: Tomcat's reply to `Expect: 100-continue` has no reason phrase, which botocore releases
    before mid-2025 cannot parse.)
  - The AWS CLI: `cp` (multipart upload and ranged download), `sync`, S3-to-S3 copy, and `rb --force`.
  - rclone: sync a tree that includes a 24 MiB file, keys with spaces, `+` and emoji, then check every byte both ways.
- **Crash test**: eight writers upload in parallel, the server is killed with `SIGKILL`, and it is restarted. Every
  acknowledged object must be present and intact, and no partial object may be visible.
- **Unit and integration tests**: storage engine recovery from torn log records, compaction, orphan cleanup, listing
  order, conditional writes, XXE rejection, and tampered streaming uploads, against the real server on a random port.

## How it works

```
                 ┌──────────────── simpan ────────────────┐
  S3 client ───► │ S3Servlet ─► SigV4 verify ─► handlers  │
                 │                    │                   │
                 │   stage ─► verify ─► commit            │
                 │   (tmp/)   (hashes)  (data/ + journal) │
                 └────────────────────────────────────────┘
data/
  data/xx/<blob>          immutable object bytes, one file per part
  meta/journal-N.log      append-only metadata log, one CRC-protected JSON line per change
  meta/snapshot-N.json    compacted state; the log restarts after each snapshot
  multipart/<uploadId>/   parts of uploads in progress (they survive restarts)
  tmp/                    uploads being received
```

**A write** streams the body into `tmp/` while computing MD5 and the requested checksum (and SHA-256 or chunk
signatures when the client signed the payload). Only when every check passes is the file flushed and renamed into
`data/`, an atomic step. Then, holding the metadata lock, simpan checks the preconditions, appends one line to the
journal, flushes it, and updates the in-memory index. Readers never take the lock: they see the old object or the new
one, never a mix.

**Recovery** loads the newest snapshot, replays the journal, and cuts off a torn final line (its CRC fails). Blob files
that no object references (from a crash between the rename and the journal write) are deleted.

**Multipart completion** doesn't copy data: part files are hard-linked into the blob store as the object's segments,
and the full-object CRC is computed by *combining* the part CRCs mathematically
([`CrcCombine`](src/main/java/io/github/simpan/io/CrcCombine.java), zlib's method generalised to CRC-64), so
completing an upload takes time proportional to the number of parts, not their size.

**Listing order** is UTF-8 byte order, as in S3. Java's `String.compareTo` would sort emoji before characters like
`Ａ`; [`KeyOrder`](src/main/java/io/github/simpan/storage/KeyOrder.java) compares code points instead.

## Configuration

| Environment variable | Default | |
|---|---|---|
| `SIMPAN_ACCESS_KEY`, `SIMPAN_SECRET_KEY` | (required) | Credentials; the secret must be at least 8 characters |
| `SIMPAN_DATA_DIR` | `./data` | Where everything is stored |
| `SIMPAN_REGION` | `us-east-1` | The region clients sign for |
| `SIMPAN_PORT` | `9000` | S3 API port |
| `SIMPAN_MANAGEMENT_PORT` | `9001` | `/actuator/health` and `/actuator/prometheus` |

More key pairs can be listed under `simpan.credentials` in `application.yml`. `simpan.domain` enables
virtual-hosted-style addressing (`bucket.example.com`); path style (`example.com/bucket`) always works.
`simpan.fsync=false` makes writes faster and less durable (useful for tests).

Metrics include `simpan_requests_seconds` (by S3 operation and status), `simpan_bytes_received`/`sent`,
`simpan_objects` and `simpan_stored_bytes`.

## Development

```bash
mvn verify                                                     # unit + integration tests
SIMPAN_ACCESS_KEY=... SIMPAN_SECRET_KEY=... python3 scripts/e2e.py   # against a running server
scripts/rclone-e2e.sh
python3 scripts/crash-test.py "java -jar target/simpan.jar"
```

## License

[Apache License 2.0](LICENSE) © Ahmad Amirizal Rahmat. Amazon S3 is a trademark of Amazon.com, Inc.; simpan is an
independent project that implements the S3 API.
