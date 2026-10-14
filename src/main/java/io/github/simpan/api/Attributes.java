package io.github.simpan.api;

import io.github.simpan.storage.ObjectAttributes;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads content headers and x-amz-meta-* user metadata from an upload request. */
final class Attributes {

    private static final int MAX_USER_METADATA = 2 * 1024;

    private Attributes() {}

    static ObjectAttributes from(HttpServletRequest req) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String h : Http.CONTENT_HEADERS) {
            String v = req.getHeader(h);
            if (v == null) continue;
            if (h.equals("Content-Encoding")) {
                v = stripAwsChunked(v);
                if (v.isEmpty()) continue;
            }
            headers.put(h, v);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        int size = 0;
        for (String name : Collections.list(req.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("x-amz-meta-")) continue;
            String k = lower.substring("x-amz-meta-".length());
            String v = String.join(",", Collections.list(req.getHeaders(name)));
            size += k.getBytes(StandardCharsets.UTF_8).length + v.getBytes(StandardCharsets.UTF_8).length;
            meta.put(k, v);
        }
        if (size > MAX_USER_METADATA) {
            throw new S3Exception(S3Error.METADATA_TOO_LARGE).with("MaxSizeAllowed", String.valueOf(MAX_USER_METADATA));
        }
        return new ObjectAttributes(headers, meta);
    }

    /** "aws-chunked" describes the request framing, not the object, so it is never stored. */
    static String stripAwsChunked(String contentEncoding) {
        List<String> kept = new ArrayList<>();
        for (String part : contentEncoding.split(",")) {
            String t = part.trim();
            if (!t.isEmpty() && !t.equalsIgnoreCase("aws-chunked")) kept.add(t);
        }
        return String.join(",", kept);
    }
}
