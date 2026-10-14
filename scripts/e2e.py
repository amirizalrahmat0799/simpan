#!/usr/bin/env python3
"""End-to-end compatibility test: drives a running simpan server with boto3, the AWS SDK for Python.

Usage: SIMPAN_ENDPOINT=http://127.0.0.1:9000 SIMPAN_ACCESS_KEY=... SIMPAN_SECRET_KEY=... python3 scripts/e2e.py
"""
import base64
import hashlib
import os
import sys
import time
import urllib.request
import zlib
from datetime import datetime, timezone

import boto3
import botocore
from botocore.config import Config
from botocore.exceptions import ClientError

ENDPOINT = os.environ.get("SIMPAN_ENDPOINT", "http://127.0.0.1:9000")
ACCESS = os.environ["SIMPAN_ACCESS_KEY"]
SECRET = os.environ["SIMPAN_SECRET_KEY"]

s3 = boto3.client("s3", endpoint_url=ENDPOINT, aws_access_key_id=ACCESS, aws_secret_access_key=SECRET,
                  region_name="us-east-1", config=Config(s3={"addressing_style": "path"}, retries={"max_attempts": 1}))
MiB = 1024 * 1024
passed = 0


def check(name, cond, detail=""):
    global passed
    if not cond:
        print(f"FAIL  {name} {detail}")
        sys.exit(1)
    passed += 1
    print(f"ok    {name}")


def error_code(fn, *args, **kwargs):
    try:
        fn(*args, **kwargs)
    except ClientError as e:
        return e.response["Error"]["Code"], e.response["ResponseMetadata"]["HTTPStatusCode"]
    return None, 200


def has_param(operation, param):
    """Older botocore releases predate some S3 features (conditional writes, checksum types); skip those."""
    return param in s3.meta.service_model.operation_model(operation).input_shape.members


def skip(name):
    print(f"skip  {name} (needs a newer botocore than {botocore.__version__})")


def b64(data):
    return base64.b64encode(data).decode()


bucket = f"e2e-{int(time.time())}"

# ---------------------------------------------------------------- buckets
s3.create_bucket(Bucket=bucket)
check("create bucket", any(b["Name"] == bucket for b in s3.list_buckets()["Buckets"]))
check("head bucket", s3.head_bucket(Bucket=bucket)["ResponseMetadata"]["HTTPStatusCode"] == 200)
check("bucket location", s3.get_bucket_location(Bucket=bucket).get("LocationConstraint") in (None, ""))
check("duplicate bucket", error_code(s3.create_bucket, Bucket=bucket)[0] == "BucketAlreadyOwnedByYou")
check("invalid bucket name", error_code(s3.create_bucket, Bucket="Bad_Name")[0] == "InvalidBucketName")
check("missing bucket", error_code(s3.head_bucket, Bucket="no-such-bucket-xyz")[1] == 404)

# ---------------------------------------------------------------- objects
body = b"Selamat datang ke simpan!\n" * 100
put = s3.put_object(Bucket=bucket, Key="docs/welcome.txt", Body=body, ContentType="text/plain; charset=utf-8",
                    Metadata={"author": "mizal"}, CacheControl="max-age=60")
check("put etag is md5", put["ETag"].strip('"') == hashlib.md5(body).hexdigest())
got = s3.get_object(Bucket=bucket, Key="docs/welcome.txt", ChecksumMode="ENABLED")
check("get body", got["Body"].read() == body)
check("content type byte for byte", got["ContentType"] == "text/plain; charset=utf-8")
check("user metadata", got["Metadata"] == {"author": "mizal"})
check("cache control", got["CacheControl"] == "max-age=60")
check("checksum returned", any(h.startswith("x-amz-checksum-") and h != "x-amz-checksum-type"
                              for h in got["ResponseMetadata"]["HTTPHeaders"]))

crc = zlib.crc32(body).to_bytes(4, "big")
s3.put_object(Bucket=bucket, Key="crc.txt", Body=body, ChecksumCRC32=b64(crc))
check("crc32 verified", s3.head_object(Bucket=bucket, Key="crc.txt", ChecksumMode="ENABLED")["ChecksumCRC32"] == b64(crc))
check("wrong crc32 rejected", error_code(s3.put_object, Bucket=bucket, Key="bad.txt", Body=body,
                                         ChecksumCRC32=b64(b"\0\0\0\0"))[0] == "BadDigest")
check("wrong md5 rejected", error_code(s3.put_object, Bucket=bucket, Key="bad.txt", Body=body,
                                       ContentMD5=b64(hashlib.md5(b"other").digest()))[0] == "BadDigest")
s3.put_object(Bucket=bucket, Key="sha.bin", Body=body, ChecksumAlgorithm="SHA256")
check("sha256 checksum", s3.head_object(Bucket=bucket, Key="sha.bin", ChecksumMode="ENABLED")["ChecksumSHA256"]
      == b64(hashlib.sha256(body).digest()))
if "CRC64NVME" in s3.meta.service_model.operation_model("PutObject").input_shape.members["ChecksumAlgorithm"].enum:
    s3.put_object(Bucket=bucket, Key="nvme.bin", Body=body, ChecksumAlgorithm="CRC64NVME")
    check("crc64nvme checksum", "ChecksumCRC64NVME" in s3.head_object(Bucket=bucket, Key="nvme.bin", ChecksumMode="ENABLED"))
else:  # botocore older than 1.36 predates CRC64NVME
    s3.put_object(Bucket=bucket, Key="nvme.bin", Body=body)
s3.put_object(Bucket=bucket, Key="empty", Body=b"")
check("empty object", s3.get_object(Bucket=bucket, Key="empty")["Body"].read() == b"")
check("missing key", error_code(s3.get_object, Bucket=bucket, Key="nope")[0] == "NoSuchKey")

# ---------------------------------------------------------------- ranges and conditions
r = s3.get_object(Bucket=bucket, Key="docs/welcome.txt", Range="bytes=0-6")
check("range first bytes", r["Body"].read() == body[:7] and r["ResponseMetadata"]["HTTPStatusCode"] == 206)
check("suffix range", s3.get_object(Bucket=bucket, Key="docs/welcome.txt", Range="bytes=-5")["Body"].read() == body[-5:])
check("open range", s3.get_object(Bucket=bucket, Key="docs/welcome.txt", Range="bytes=2590-")["Body"].read() == body[2590:])
check("unsatisfiable range", error_code(s3.get_object, Bucket=bucket, Key="docs/welcome.txt",
                                        Range="bytes=99999-")[0] == "InvalidRange")
etag = put["ETag"]
check("if-none-match 304", error_code(s3.get_object, Bucket=bucket, Key="docs/welcome.txt", IfNoneMatch=etag)[1] == 304)
check("if-match 412", error_code(s3.get_object, Bucket=bucket, Key="docs/welcome.txt", IfMatch='"0000"')[1] == 412)
check("if-modified-since 304", error_code(s3.get_object, Bucket=bucket, Key="docs/welcome.txt",
                                          IfModifiedSince=datetime.now(timezone.utc))[1] == 304)
if has_param("PutObject", "IfNoneMatch") and has_param("PutObject", "IfMatch"):
    check("conditional create", error_code(s3.put_object, Bucket=bucket, Key="docs/welcome.txt", Body=b"x",
                                           IfNoneMatch="*")[0] == "PreconditionFailed")
    s3.put_object(Bucket=bucket, Key="new-only.txt", Body=b"first", IfNoneMatch="*")
    check("conditional create of a new key", s3.get_object(Bucket=bucket, Key="new-only.txt")["Body"].read() == b"first")
    check("if-match write mismatch", error_code(s3.put_object, Bucket=bucket, Key="new-only.txt", Body=b"x",
                                                IfMatch='"0000"')[0] == "PreconditionFailed")
else:
    skip("conditional writes")
    s3.put_object(Bucket=bucket, Key="new-only.txt", Body=b"first")

# ---------------------------------------------------------------- listing
keys = [f"photos/2026/{i:02d}.jpg" for i in range(12)] + [f"photos/2025/{i:02d}.jpg" for i in range(3)]
keys += ["notes/a b+c.txt", "notes/emoji-\U0001F600.txt", "notes/Ａ-fullwidth.txt", "notes/x%2Fy.txt"]
for k in keys:
    s3.put_object(Bucket=bucket, Key=k, Body=k.encode())
listed, token = [], None
while True:
    args = {"Bucket": bucket, "Prefix": "photos/", "MaxKeys": 4}
    if token:
        args["ContinuationToken"] = token
    page = s3.list_objects_v2(**args)
    listed += [o["Key"] for o in page.get("Contents", [])]
    if not page["IsTruncated"]:
        break
    token = page["NextContinuationToken"]
check("v2 pagination", listed == sorted(k for k in keys if k.startswith("photos/")))
page = s3.list_objects_v2(Bucket=bucket, Prefix="photos/", Delimiter="/")
check("common prefixes", [p["Prefix"] for p in page["CommonPrefixes"]] == ["photos/2025/", "photos/2026/"])
page = s3.list_objects_v2(Bucket=bucket, Prefix="photos/", Delimiter="/", MaxKeys=1)
page2 = s3.list_objects_v2(Bucket=bucket, Prefix="photos/", Delimiter="/", ContinuationToken=page["NextContinuationToken"])
check("prefix pagination", [p["Prefix"] for p in page2["CommonPrefixes"]] == ["photos/2026/"])
notes = [o["Key"] for o in s3.list_objects_v2(Bucket=bucket, Prefix="notes/")["Contents"]]
check("utf-8 binary order and special characters", notes == sorted(notes, key=lambda k: k.encode()) and
      set(notes) == {k for k in keys if k.startswith("notes/")}, str(notes))
check("odd keys round trip", all(s3.get_object(Bucket=bucket, Key=k)["Body"].read() == k.encode()
                                 for k in keys if k.startswith("notes/")))
v1 = s3.list_objects(Bucket=bucket, Prefix="photos/2026/", Marker="photos/2026/09.jpg")
check("v1 marker", [o["Key"] for o in v1["Contents"]] == ["photos/2026/10.jpg", "photos/2026/11.jpg"])
paginator = s3.get_paginator("list_objects_v2")
total = sum(len(p.get("Contents", [])) for p in paginator.paginate(Bucket=bucket, PaginationConfig={"PageSize": 3}))
check("paginator", total == len(keys) + 6, str(total))

# ---------------------------------------------------------------- copy and batch delete
s3.copy_object(Bucket=bucket, Key="copy.txt", CopySource={"Bucket": bucket, "Key": "docs/welcome.txt"})
c = s3.get_object(Bucket=bucket, Key="copy.txt")
check("copy keeps metadata", c["Body"].read() == body and c["Metadata"] == {"author": "mizal"})
s3.copy_object(Bucket=bucket, Key="copy.txt", CopySource={"Bucket": bucket, "Key": "copy.txt"},
               MetadataDirective="REPLACE", Metadata={"v": "2"}, ContentType="text/markdown")
c = s3.head_object(Bucket=bucket, Key="copy.txt")
check("copy replaces metadata", c["Metadata"] == {"v": "2"} and c["ContentType"] == "text/markdown")
check("copy onto itself", error_code(s3.copy_object, Bucket=bucket, Key="copy.txt",
                                     CopySource={"Bucket": bucket, "Key": "copy.txt"})[0] == "InvalidRequest")
res = s3.delete_objects(Bucket=bucket, Delete={"Objects": [{"Key": "photos/2025/00.jpg"}, {"Key": "never-existed"}]})
check("batch delete", len(res["Deleted"]) == 2 and error_code(s3.head_object, Bucket=bucket,
                                                              Key="photos/2025/00.jpg")[1] == 404)

# ---------------------------------------------------------------- multipart
data = os.urandom(13 * MiB)
algorithms = ["SHA256", "CRC32C"]
if "CRC64NVME" in s3.meta.service_model.operation_model("PutObject").input_shape.members["ChecksumAlgorithm"].enum:
    algorithms.append("CRC64NVME")
for algorithm in algorithms:
    key = f"mp-{algorithm}.bin"
    up = s3.create_multipart_upload(Bucket=bucket, Key=key, ChecksumAlgorithm=algorithm, ContentType="video/mp4")
    parts = []
    for n, off in enumerate(range(0, len(data), 5 * MiB), start=1):
        p = s3.upload_part(Bucket=bucket, Key=key, UploadId=up["UploadId"], PartNumber=n, Body=data[off:off + 5 * MiB],
                           ChecksumAlgorithm=algorithm)
        parts.append({"PartNumber": n, "ETag": p["ETag"], f"Checksum{algorithm}": p[f"Checksum{algorithm}"]})
    lp = s3.list_parts(Bucket=bucket, Key=key, UploadId=up["UploadId"])
    check(f"list parts {algorithm}", [p["PartNumber"] for p in lp["Parts"]] == [1, 2, 3])
    done = s3.complete_multipart_upload(Bucket=bucket, Key=key, UploadId=up["UploadId"],
                                        MultipartUpload={"Parts": parts})
    expected_etag = hashlib.md5(b"".join(hashlib.md5(data[o:o + 5 * MiB]).digest()
                                         for o in range(0, len(data), 5 * MiB))).hexdigest() + "-3"
    check(f"multipart etag {algorithm}", done["ETag"].strip('"') == expected_etag)
    check(f"multipart data {algorithm}", s3.get_object(Bucket=bucket, Key=key)["Body"].read() == data)
    part2 = s3.get_object(Bucket=bucket, Key=key, PartNumber=2)
    check(f"get part 2 {algorithm}", part2["Body"].read() == data[5 * MiB:10 * MiB] and part2["PartsCount"] == 3)
    head = s3.head_object(Bucket=bucket, Key=key, ChecksumMode="ENABLED")
    check(f"multipart checksum {algorithm}", head[f"Checksum{algorithm}"] and head["ContentType"] == "video/mp4")

# full-object CRC32 must equal the CRC32 of the whole object
if has_param("CreateMultipartUpload", "ChecksumType"):
    up = s3.create_multipart_upload(Bucket=bucket, Key="full-crc.bin", ChecksumAlgorithm="CRC32",
                                    ChecksumType="FULL_OBJECT")
    parts = []
    for n, off in enumerate(range(0, len(data), 5 * MiB), start=1):
        p = s3.upload_part(Bucket=bucket, Key="full-crc.bin", UploadId=up["UploadId"], PartNumber=n,
                           Body=data[off:off + 5 * MiB], ChecksumAlgorithm="CRC32")
        parts.append({"PartNumber": n, "ETag": p["ETag"]})
    s3.complete_multipart_upload(Bucket=bucket, Key="full-crc.bin", UploadId=up["UploadId"],
                                 MultipartUpload={"Parts": parts})
    head = s3.head_object(Bucket=bucket, Key="full-crc.bin", ChecksumMode="ENABLED")
    check("full-object crc32 combine", head["ChecksumCRC32"] == b64(zlib.crc32(data).to_bytes(4, "big"))
          and head["ChecksumType"] == "FULL_OBJECT")
else:
    skip("full-object multipart checksums")

up = s3.create_multipart_upload(Bucket=bucket, Key="small.bin")
p1 = s3.upload_part(Bucket=bucket, Key="small.bin", UploadId=up["UploadId"], PartNumber=1, Body=b"tiny")
p2 = s3.upload_part(Bucket=bucket, Key="small.bin", UploadId=up["UploadId"], PartNumber=2, Body=b"tiny")
check("part too small", error_code(s3.complete_multipart_upload, Bucket=bucket, Key="small.bin", UploadId=up["UploadId"],
                                   MultipartUpload={"Parts": [{"PartNumber": 1, "ETag": p1["ETag"]},
                                                              {"PartNumber": 2, "ETag": p2["ETag"]}]})[0] == "EntityTooSmall")
check("wrong part etag", error_code(s3.complete_multipart_upload, Bucket=bucket, Key="small.bin", UploadId=up["UploadId"],
                                    MultipartUpload={"Parts": [{"PartNumber": 1, "ETag": '"bad"'}]})[0] == "InvalidPart")
check("list uploads", any(u["UploadId"] == up["UploadId"] for u in s3.list_multipart_uploads(Bucket=bucket)["Uploads"]))
s3.abort_multipart_upload(Bucket=bucket, Key="small.bin", UploadId=up["UploadId"])
check("abort", error_code(s3.list_parts, Bucket=bucket, Key="small.bin", UploadId=up["UploadId"])[0] == "NoSuchUpload")

up = s3.create_multipart_upload(Bucket=bucket, Key="part-copy.bin")
pc = s3.upload_part_copy(Bucket=bucket, Key="part-copy.bin", UploadId=up["UploadId"], PartNumber=1,
                         CopySource={"Bucket": bucket, "Key": "mp-SHA256.bin"}, CopySourceRange="bytes=0-5242879")
p2 = s3.upload_part(Bucket=bucket, Key="part-copy.bin", UploadId=up["UploadId"], PartNumber=2, Body=b"tail")
s3.complete_multipart_upload(Bucket=bucket, Key="part-copy.bin", UploadId=up["UploadId"], MultipartUpload={
    "Parts": [{"PartNumber": 1, "ETag": pc["CopyPartResult"]["ETag"]}, {"PartNumber": 2, "ETag": p2["ETag"]}]})
check("upload part copy", s3.get_object(Bucket=bucket, Key="part-copy.bin")["Body"].read() == data[:5 * MiB] + b"tail")

# high-level transfer manager (what aws s3 cp uses)
path = "/tmp/simpan-e2e.bin"
with open(path, "wb") as f:
    f.write(data)
s3.upload_file(path, bucket, "transfer.bin")
s3.download_file(bucket, "transfer.bin", path + ".back")
check("upload_file / download_file", open(path + ".back", "rb").read() == data)

# ---------------------------------------------------------------- presigned URLs and auth
v4 = boto3.client("s3", endpoint_url=ENDPOINT, aws_access_key_id=ACCESS, aws_secret_access_key=SECRET,
                  region_name="us-east-1", config=Config(signature_version="s3v4", s3={"addressing_style": "path"}))
url = s3.generate_presigned_url("get_object", Params={"Bucket": bucket, "Key": "docs/welcome.txt"}, ExpiresIn=60)
check("presigned get (SigV2, boto3's default)", "AWSAccessKeyId=" in url and urllib.request.urlopen(url).read() == body)
url = v4.generate_presigned_url("get_object", Params={"Bucket": bucket, "Key": "docs/welcome.txt"}, ExpiresIn=60)
check("presigned get (SigV4)", "X-Amz-Signature=" in url and urllib.request.urlopen(url).read() == body)
url = v4.generate_presigned_url("put_object", Params={"Bucket": bucket, "Key": "presigned.txt"}, ExpiresIn=60)
urllib.request.urlopen(urllib.request.Request(url, data=b"via presigned url", method="PUT"))
check("presigned put (SigV4)", s3.get_object(Bucket=bucket, Key="presigned.txt")["Body"].read() == b"via presigned url")
url = v4.generate_presigned_url("get_object", Params={"Bucket": bucket, "Key": "docs/welcome.txt"}, ExpiresIn=1)
time.sleep(2)
try:
    urllib.request.urlopen(url)
    check("expired presigned url", False)
except urllib.error.HTTPError as e:
    check("expired presigned url", e.code == 403)
tampered = v4.generate_presigned_url("get_object", Params={"Bucket": bucket, "Key": "docs/welcome.txt"}, ExpiresIn=60)
try:
    urllib.request.urlopen(tampered.replace("welcome", "welcomf"))
    check("tampered presigned url", False)
except urllib.error.HTTPError as e:
    check("tampered presigned url", e.code == 403)
bad = boto3.client("s3", endpoint_url=ENDPOINT, aws_access_key_id=ACCESS, aws_secret_access_key=SECRET + "x",
                   region_name="us-east-1")
check("wrong secret", error_code(bad.list_buckets)[0] == "SignatureDoesNotMatch")
unknown = boto3.client("s3", endpoint_url=ENDPOINT, aws_access_key_id="nobody", aws_secret_access_key=SECRET,
                       region_name="us-east-1")
check("unknown access key", error_code(unknown.list_buckets)[0] == "InvalidAccessKeyId")

# ---------------------------------------------------------------- cleanup
check("bucket not empty", error_code(s3.delete_bucket, Bucket=bucket)[0] == "BucketNotEmpty")
for page in paginator.paginate(Bucket=bucket):
    objs = [{"Key": o["Key"]} for o in page.get("Contents", [])]
    if objs:
        s3.delete_objects(Bucket=bucket, Delete={"Objects": objs, "Quiet": True})
s3.delete_bucket(Bucket=bucket)
check("delete bucket", error_code(s3.head_bucket, Bucket=bucket)[1] == 404)
print(f"\nAll {passed} checks passed against {ENDPOINT} (boto3 {boto3.__version__}, botocore {botocore.__version__})")
