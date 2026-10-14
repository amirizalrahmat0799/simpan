package io.github.simpan.api;

import io.github.simpan.auth.AuthResult;
import io.github.simpan.auth.SigV4;
import io.github.simpan.auth.SigV4Verifier;
import io.github.simpan.auth.SignableRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A parsed S3 request: bucket and key from the path (or the Host header), query parameters, headers. */
public final class S3Request implements SignableRequest, io.github.simpan.auth.SigV2Verifier.HeaderNames {

    private final HttpServletRequest http;
    private final String rawPath;
    private final String rawQuery;
    private final Map<String, String> query;
    private final String bucket;
    private final String key;
    private final boolean virtualHost;
    private AuthResult auth;

    S3Request(HttpServletRequest http, String domain) {
        this.http = http;
        String uri = http.getRequestURI();
        this.rawPath = uri == null || uri.isEmpty() ? "/" : uri;
        this.rawQuery = http.getQueryString();
        this.query = SigV4Verifier.parseQuery(rawQuery);
        String host = http.getHeader("host");
        String hostBucket = null;
        if (domain != null && host != null) {
            String h = host.toLowerCase(Locale.ROOT);
            int colon = h.lastIndexOf(':');
            if (colon > h.lastIndexOf(']')) h = h.substring(0, colon);
            if (h.endsWith("." + domain)) hostBucket = h.substring(0, h.length() - domain.length() - 1);
        }
        String path = rawPath.substring(1);
        virtualHost = hostBucket != null;
        if (hostBucket != null) {
            bucket = hostBucket;
            key = path.isEmpty() ? null : SigV4.percentDecode(path);
        } else if (path.isEmpty()) {
            bucket = null;
            key = null;
        } else {
            int slash = path.indexOf('/');
            bucket = SigV4.percentDecode(slash < 0 ? path : path.substring(0, slash));
            String k = slash < 0 ? "" : path.substring(slash + 1);
            key = k.isEmpty() ? null : SigV4.percentDecode(k);
        }
    }

    public String bucket() {
        return bucket;
    }

    public String key() {
        return key;
    }

    public boolean has(String param) {
        return query.containsKey(param);
    }

    public String param(String name) {
        return query.get(name);
    }

    public Map<String, String> params() {
        return query;
    }

    public AuthResult auth() {
        return auth;
    }

    void auth(AuthResult auth) {
        this.auth = auth;
    }

    public HttpServletRequest http() {
        return http;
    }

    public InputStream body() throws IOException {
        return http.getInputStream();
    }

    public long contentLength() {
        return http.getContentLengthLong();
    }

    @Override
    public String method() {
        return http.getMethod();
    }

    @Override
    public String rawPath() {
        return rawPath;
    }

    @Override
    public String rawQuery() {
        return rawQuery;
    }

    @Override
    public List<String> headers(String name) {
        return Collections.list(http.getHeaders(name));
    }

    @Override
    public String virtualHostBucketPrefix() {
        return virtualHost ? "/" + bucket : "";
    }

    @Override
    public List<String> headerNames() {
        return Collections.list(http.getHeaderNames());
    }

    /** "/bucket/key" for error documents. */
    public String resource() {
        return "/" + (bucket == null ? "" : bucket) + (key == null ? "" : "/" + key);
    }
}
