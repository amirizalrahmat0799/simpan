package io.github.simpan.auth;

import java.util.List;

/** The parts of an HTTP request that SigV4 signs, independent of the servlet API. */
public interface SignableRequest {
    String method();

    /** Path exactly as received, still percent-encoded. */
    String rawPath();

    /** Query string exactly as received (without '?'), or null. */
    String rawQuery();

    /** All values of a header, case-insensitive name; empty if absent. */
    List<String> headers(String name);

    /** For virtual-hosted-style requests, "/bucket", which SigV2 adds to the signed resource. */
    default String virtualHostBucketPrefix() {
        return "";
    }

    default String header(String name) {
        List<String> v = headers(name);
        return v.isEmpty() ? null : v.get(0);
    }
}
