package io.github.simpan.storage;

import java.nio.file.Path;

/** Uploaded bytes sitting in a temporary file, not yet part of any object. */
public record StagedBlob(Path file, long size, byte[] md5) {}
