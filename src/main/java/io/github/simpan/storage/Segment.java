package io.github.simpan.storage;

/** A blob file holding a contiguous slice of an object's bytes (one per multipart part). */
public record Segment(String blob, long size) {}
