package io.github.simpan.storage;

import java.util.concurrent.ConcurrentSkipListMap;

/** A bucket and its objects, sorted in S3 key order. */
record BucketState(BucketInfo info, ConcurrentSkipListMap<String, ObjectMeta> objects) {}
