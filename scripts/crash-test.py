#!/usr/bin/env python3
"""Crash test: uploads objects from several threads, kills the server with SIGKILL mid-stream, restarts it, and
checks that every object the server acknowledged is intact and that no partial object is visible.

Usage: python3 scripts/crash-test.py "<command that starts simpan>"   (SIMPAN_* environment as for the server)
"""
import hashlib
import os
import signal
import subprocess
import sys
import threading
import time
import urllib.request

import boto3
from botocore.config import Config

cmd = sys.argv[1]
env = dict(os.environ)
endpoint = env.get("SIMPAN_ENDPOINT", "http://127.0.0.1:9000")
s3 = boto3.client("s3", endpoint_url=endpoint, aws_access_key_id=env["SIMPAN_ACCESS_KEY"],
                  aws_secret_access_key=env["SIMPAN_SECRET_KEY"], region_name="us-east-1",
                  config=Config(retries={"max_attempts": 1}, connect_timeout=2, read_timeout=10))


def start():
    p = subprocess.Popen(cmd, shell=True, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=True)
    for _ in range(120):
        try:
            urllib.request.urlopen(env.get("SIMPAN_HEALTH", "http://127.0.0.1:9001/actuator/health"), timeout=1)
            return p
        except Exception:
            time.sleep(0.25)
    raise SystemExit("server did not start")


server = start()
s3.create_bucket(Bucket="crash")
acked = {}
lock = threading.Lock()
stop = threading.Event()


def writer(n):
    i = 0
    while not stop.is_set():
        key = f"w{n}/obj-{i:05d}"
        body = os.urandom(1024 * (1 + (i * 37 + n) % 900))
        try:
            s3.put_object(Bucket="crash", Key=key, Body=body)
            with lock:
                acked[key] = hashlib.md5(body).hexdigest()
        except Exception:
            return
        i += 1


threads = [threading.Thread(target=writer, args=(n,)) for n in range(8)]
for t in threads:
    t.start()
time.sleep(4)
os.killpg(server.pid, signal.SIGKILL)
stop.set()
for t in threads:
    t.join()
print(f"killed the server after {len(acked)} acknowledged uploads")

server = start()
listed = {}
for page in s3.get_paginator("list_objects_v2").paginate(Bucket="crash"):
    for o in page.get("Contents", []):
        listed[o["Key"]] = o["ETag"].strip('"')
missing = [k for k in acked if k not in listed]
corrupt = []
for key, etag in listed.items():
    body = s3.get_object(Bucket="crash", Key=key)["Body"].read()
    if hashlib.md5(body).hexdigest() != etag or (key in acked and acked[key] != etag):
        corrupt.append(key)
os.killpg(server.pid, signal.SIGKILL)
print(f"after restart: {len(listed)} objects listed, {len(missing)} acknowledged-but-missing, {len(corrupt)} corrupt")
if missing or corrupt:
    raise SystemExit("FAILED")
print("crash test passed: every acknowledged write survived and no object is partial")
