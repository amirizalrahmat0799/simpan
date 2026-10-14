package io.github.simpan.api;

import io.github.simpan.storage.ObjectMeta;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextResponse;
import org.eclipse.jetty.http.HttpHeader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Response helpers and request validation shared by the handlers. */
final class Http {

    static final String OWNER_ID = "6e8cfdb7c1b0b9f9b1a1c4d7a5c52fb0f1d3c2b7e9a8d6c5b4a3f2e1d0c9b8a7";
    static final String OWNER_NAME = "simpan";
    static final long MAX_PUT_SIZE = 5L * 1024 * 1024 * 1024;
    static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    static final int MAX_PART_NUMBER = 10_000;

    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    private static final Pattern IP = Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+");

    /** Content headers stored with an object and returned on GET/HEAD. */
    static final String[] CONTENT_HEADERS = {"Content-Type", "Content-Encoding", "Content-Disposition",
        "Content-Language", "Cache-Control", "Expires"};

    private Http() {}

    static void xml(HttpServletResponse res, int status, Xml xml) throws IOException {
        byte[] body = xml.bytes();
        res.setStatus(status);
        res.setContentType("application/xml");
        res.setContentLength(body.length);
        res.getOutputStream().write(body);
    }

    static String httpDate(long millis) {
        return HTTP_DATE.format(Instant.ofEpochMilli(millis));
    }

    static Long parseHttpDate(String v) {
        if (v == null) return null;
        try {
            return ZonedDateTime.parse(v.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static void validateBucketName(String name) {
        if (name == null || !BUCKET.matcher(name).matches() || name.contains("..") || name.contains(".-")
                || name.contains("-.") || IP.matcher(name).matches() || name.startsWith("xn--")
                || name.endsWith("-s3alias")) {
            throw new S3Exception(S3Error.INVALID_BUCKET_NAME).with("BucketName", name);
        }
    }

    static void validateKey(String key) {
        if (key.getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw new S3Exception(S3Error.KEY_TOO_LONG).with("Size", String.valueOf(key.length())).with("MaxSizeAllowed", "1024");
        }
    }

    /** Writes the stored headers of an object: ETag, Last-Modified, content headers, user metadata. */
    static void objectHeaders(HttpServletResponse res, ObjectMeta m) {
        res.setHeader("ETag", m.etag());
        res.setHeader("Last-Modified", httpDate(m.lastModified()));
        res.setHeader("Accept-Ranges", "bytes");
        for (String h : CONTENT_HEADERS) {
            String v = m.headers().get(h);
            if (v != null) header(res, h, v);
        }
        if (!m.headers().containsKey("Content-Type")) header(res, "Content-Type", "binary/octet-stream");
        for (Map.Entry<String, String> e : m.userMetadata().entrySet()) res.setHeader("x-amz-meta-" + e.getKey(), e.getValue());
        if (m.partsCount() > 0) res.setHeader("x-amz-mp-parts-count", String.valueOf(m.partsCount()));
    }

    /**
     * Sets a response header exactly as stored. Content-Type goes straight into Jetty's header fields: the servlet
     * API would parse and re-render it ("text/plain; charset=utf-8" became "text/plain;charset=utf-8"), and S3
     * returns the value the uploader sent, byte for byte.
     */
    static void header(HttpServletResponse res, String name, String value) {
        if (name.equalsIgnoreCase("Content-Type")) {
            ServletContextResponse jetty = ServletContextResponse.getServletContextResponse(res);
            if (jetty != null) {
                jetty.getWrapped().getHeaders().put(HttpHeader.CONTENT_TYPE, value);
                return;
            }
        }
        res.setHeader(name, value);
    }

    static String quote(String etag) {
        return etag.startsWith("\"") ? etag : "\"" + etag + "\"";
    }
}
