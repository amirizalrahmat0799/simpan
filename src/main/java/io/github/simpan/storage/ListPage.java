package io.github.simpan.storage;

import java.util.List;

/**
 * One page of a listing.
 *
 * @param last    the last key or common prefix on this page, for continuation; null if the page is empty
 * @param lastIsPrefix whether {@code last} is a common prefix
 */
public record ListPage(List<ObjectMeta> objects, List<String> commonPrefixes, boolean truncated, String last,
        boolean lastIsPrefix) {}
