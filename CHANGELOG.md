# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- S3 API on Spring Boot 3.5 and Java 21: buckets, objects, ListObjects V1/V2, CopyObject, DeleteObjects, multipart
  uploads (including UploadPartCopy), ranges, partNumber reads, conditional reads and writes, presigned URLs.
- Authentication: Signature V4 (header, presigned, streaming with signed chunks and trailers, unsigned trailers) and
  Signature V2, verified against AWS's published examples.
- Checksums: CRC32, CRC32C, CRC64NVME, SHA-1, SHA-256 as headers or trailers; full-object multipart checksums by CRC
  combination; composite checksums.
- Storage engine: immutable blobs with atomic renames, CRC-protected metadata journal with snapshots and compaction,
  crash recovery, orphan cleanup, multipart uploads that survive restarts.
- Prometheus metrics, health probes, Dockerfile and Compose file.
- Compatibility tests with boto3, the AWS CLI and rclone, and a SIGKILL crash test, run in CI.
