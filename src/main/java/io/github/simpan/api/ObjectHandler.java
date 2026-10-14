package io.github.simpan.api;

import io.github.simpan.auth.SigV4;
import io.github.simpan.io.ChecksumAlgorithm;
import io.github.simpan.metrics.S3Metrics;
import io.github.simpan.storage.ObjectAttributes;
import io.github.simpan.storage.ObjectMeta;
import io.github.simpan.storage.Precondition;
import io.github.simpan.storage.Storage;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Base64;
import java.util.Map;

/** Object operations: put, get, head, delete, copy. */
final class ObjectHandler {

    private static final Map<String, String> RESPONSE_OVERRIDES = Map.of(
            "response-content-type", "Content-Type",
            "response-content-language", "Content-Language",
            "response-expires", "Expires",
            "response-cache-control", "Cache-Control",
            "response-content-disposition", "Content-Disposition",
            "response-content-encoding", "Content-Encoding");

    private final Storage storage;
    private final UploadReader uploads;
    private final S3Metrics metrics;

    ObjectHandler(Storage storage, UploadReader uploads, S3Metrics metrics) {
        this.storage = storage;
        this.uploads = uploads;
        this.metrics = metrics;
    }

    void put(S3Request req, HttpServletResponse res) throws IOException {
        Http.validateKey(req.key());
        if (storage.bucket(req.bucket()) == null) throw new S3Exception(S3Error.NO_SUCH_BUCKET).with("BucketName", req.bucket());
        ObjectAttributes attrs = Attributes.from(req.http());
        Precondition pre = writePrecondition(req);
        UploadReader.Upload up = uploads.read(req, Http.MAX_PUT_SIZE, null);
        metrics.received(up.staged().size());
        ObjectMeta m = storage.putObject(req.bucket(), req.key(), up.staged(), attrs, up.algorithm(), up.checksum(), pre);
        res.setHeader("ETag", m.etag());
        checksumHeaders(res, m);
        res.setStatus(200);
    }

    void head(S3Request req, HttpServletResponse res) throws IOException {
        serve(req, res, false);
    }

    void get(S3Request req, HttpServletResponse res) throws IOException {
        serve(req, res, true);
    }

    private void serve(S3Request req, HttpServletResponse res, boolean body) throws IOException {
        ObjectMeta m = storage.get(req.bucket(), req.key());
        if (m == null) throw new S3Exception(S3Error.NO_SUCH_KEY).with("Key", req.key());

        int status = conditional(req, m);
        if (status == 304) {
            res.setHeader("ETag", m.etag());
            res.setHeader("Last-Modified", Http.httpDate(m.lastModified()));
            res.setStatus(304);
            return;
        }

        long offset = 0;
        long length = m.size();
        boolean partial = false;
        String partNumber = req.param("partNumber");
        if (partNumber != null) {
            int n = parsePartNumber(partNumber);
            if (m.partsCount() == 0) {
                if (n != 1) throw new S3Exception(S3Error.INVALID_RANGE).with("ActualObjectSize", String.valueOf(m.size()));
            } else {
                if (n > m.segments().size()) throw new S3Exception(S3Error.INVALID_RANGE);
                for (int i = 0; i < n - 1; i++) offset += m.segments().get(i).size();
                length = m.segments().get(n - 1).size();
                partial = true;
            }
        } else {
            long[] range = parseRange(req.header("range"), m.size());
            if (range != null) {
                offset = range[0];
                length = range[1] - range[0] + 1;
                partial = true;
            }
        }

        Http.objectHeaders(res, m);
        for (Map.Entry<String, String> o : RESPONSE_OVERRIDES.entrySet()) {
            String v = req.param(o.getKey());
            if (v != null) Http.header(res, o.getValue(), v);
        }
        if ("ENABLED".equalsIgnoreCase(req.header("x-amz-checksum-mode")) && (!partial || partNumber != null && m.partsCount() == 0)) {
            checksumHeaders(res, m);
        }
        if (partial) {
            res.setStatus(206);
            res.setHeader("Content-Range", "bytes " + offset + "-" + (offset + length - 1) + "/" + m.size());
        } else {
            res.setStatus(200);
        }
        res.setContentLengthLong(length);
        if (!body || length == 0) return;
        try (InputStream in = storage.read(m, offset, length)) {
            OutputStream out = res.getOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            long sent = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                sent += n;
            }
            metrics.sent(sent);
        }
    }

    void delete(S3Request req, HttpServletResponse res) {
        String ifMatch = req.header("if-match");
        if (ifMatch != null) {
            ObjectMeta m = storage.get(req.bucket(), req.key());
            if (m == null) throw new S3Exception(S3Error.NO_SUCH_KEY).with("Key", req.key());
            if (!Storage.etagMatches(ifMatch, m.etag())) throw new S3Exception(S3Error.PRECONDITION_FAILED).with("Condition", "If-Match");
        }
        if (storage.bucket(req.bucket()) == null) throw new S3Exception(S3Error.NO_SUCH_BUCKET).with("BucketName", req.bucket());
        storage.delete(req.bucket(), req.key());
        res.setStatus(204);
    }

    void copy(S3Request req, HttpServletResponse res) throws IOException {
        Http.validateKey(req.key());
        ObjectMeta source = copySource(req, storage);
        String directive = req.header("x-amz-metadata-directive");
        boolean replace = "REPLACE".equalsIgnoreCase(directive);
        if (directive != null && !replace && !"COPY".equalsIgnoreCase(directive)) {
            throw new S3Exception(S3Error.INVALID_ARGUMENT, "Unknown metadata directive.");
        }
        String[] src = sourceBucketKey(req);
        if (src[0].equals(req.bucket()) && src[1].equals(req.key()) && !replace) {
            throw new S3Exception(S3Error.INVALID_REQUEST, "This copy request is illegal because it is trying to copy an "
                    + "object to itself without changing the object's metadata, storage class, website redirect "
                    + "location or encryption attributes.");
        }
        ObjectMeta m = storage.copyObject(source, req.bucket(), req.key(), replace ? Attributes.from(req.http()) : null,
                writePrecondition(req));
        Xml x = new Xml().root("CopyObjectResult").el("ETag", m.etag()).time("LastModified", m.lastModified());
        ChecksumAlgorithm alg = ChecksumAlgorithm.fromApiName(m.checksumAlgorithm());
        if (alg != null && m.checksum() != null) x.el(alg.xmlElement(), m.checksum()).el("ChecksumType", m.checksumType());
        x.close("CopyObjectResult");
        Http.xml(res, 200, x);
    }

    // ------------------------------------------------------------------ helpers

    /** Resolves x-amz-copy-source and checks the x-amz-copy-source-if-* conditions. */
    static ObjectMeta copySource(S3Request req, Storage storage) {
        String[] src = sourceBucketKey(req);
        ObjectMeta m = storage.get(src[0], src[1]);
        if (m == null) throw new S3Exception(S3Error.NO_SUCH_KEY).with("Key", src[1]);
        String ifMatch = req.header("x-amz-copy-source-if-match");
        String ifNoneMatch = req.header("x-amz-copy-source-if-none-match");
        Long ifModified = Http.parseHttpDate(req.header("x-amz-copy-source-if-modified-since"));
        Long ifUnmodified = Http.parseHttpDate(req.header("x-amz-copy-source-if-unmodified-since"));
        long lastModifiedSecond = m.lastModified() / 1000 * 1000;
        if (ifMatch != null && !Storage.etagMatches(ifMatch, m.etag())) throw precondition("x-amz-copy-source-If-Match");
        if (ifMatch == null && ifUnmodified != null && lastModifiedSecond > ifUnmodified) {
            throw precondition("x-amz-copy-source-If-Unmodified-Since");
        }
        if (ifNoneMatch != null && Storage.etagMatches(ifNoneMatch, m.etag())) throw precondition("x-amz-copy-source-If-None-Match");
        if (ifNoneMatch == null && ifModified != null && lastModifiedSecond <= ifModified) {
            throw precondition("x-amz-copy-source-If-Modified-Since");
        }
        return m;
    }

    static String[] sourceBucketKey(S3Request req) {
        String raw = req.header("x-amz-copy-source");
        if (raw == null) throw new S3Exception(S3Error.INVALID_ARGUMENT, "Copy Source must mention the source bucket and key: sourcebucket/sourcekey");
        int q = raw.indexOf('?');
        if (q >= 0) {
            String query = raw.substring(q + 1);
            if (query.startsWith("versionId=") && !query.equals("versionId=null")) {
                throw new S3Exception(S3Error.NOT_IMPLEMENTED, "Versioning is not supported yet");
            }
            raw = raw.substring(0, q);
        }
        String decoded = SigV4.percentDecode(raw);
        if (decoded.startsWith("/")) decoded = decoded.substring(1);
        int slash = decoded.indexOf('/');
        if (slash <= 0 || slash == decoded.length() - 1) {
            throw new S3Exception(S3Error.INVALID_ARGUMENT, "Copy Source must mention the source bucket and key: sourcebucket/sourcekey");
        }
        return new String[] {decoded.substring(0, slash), decoded.substring(slash + 1)};
    }

    private static S3Exception precondition(String condition) {
        return new S3Exception(S3Error.PRECONDITION_FAILED).with("Condition", condition);
    }

    static Precondition writePrecondition(S3Request req) {
        String ifNoneMatch = req.header("if-none-match");
        String ifMatch = req.header("if-match");
        if (ifNoneMatch != null && !ifNoneMatch.trim().equals("*")) {
            throw new S3Exception(S3Error.NOT_IMPLEMENTED, "If-None-Match on writes only supports *");
        }
        return ifNoneMatch == null && ifMatch == null ? Precondition.NONE : new Precondition(ifNoneMatch != null, ifMatch);
    }

    /** Evaluates If-Match, If-Unmodified-Since, If-None-Match and If-Modified-Since in RFC 9110 order. */
    private static int conditional(S3Request req, ObjectMeta m) {
        String ifMatch = req.header("if-match");
        String ifNoneMatch = req.header("if-none-match");
        Long ifModified = Http.parseHttpDate(req.header("if-modified-since"));
        Long ifUnmodified = Http.parseHttpDate(req.header("if-unmodified-since"));
        long lm = m.lastModified() / 1000 * 1000;
        if (ifMatch != null) {
            if (!Storage.etagMatches(ifMatch, m.etag())) throw precondition("If-Match");
        } else if (ifUnmodified != null && lm > ifUnmodified) {
            throw precondition("If-Unmodified-Since");
        }
        if (ifNoneMatch != null) {
            if (Storage.etagMatches(ifNoneMatch, m.etag())) return 304;
        } else if (ifModified != null && lm <= ifModified) {
            return 304;
        }
        return 200;
    }

    /** Parses a single "bytes=" range; null means the whole object. Throws InvalidRange if unsatisfiable. */
    static long[] parseRange(String header, long size) {
        if (header == null) return null;
        String h = header.trim();
        if (!h.startsWith("bytes=") || h.contains(",")) return null;
        String spec = h.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        String a = spec.substring(0, dash).trim();
        String b = spec.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (a.isEmpty()) {
                if (b.isEmpty()) return null;
                long suffix = Long.parseLong(b);
                if (suffix == 0) throw unsatisfiable(size);
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? size - 1 : Math.min(Long.parseLong(b), size - 1);
                if (!b.isEmpty() && Long.parseLong(b) < start) return null;
            }
            if (start >= size || size == 0) throw unsatisfiable(size);
            return new long[] {start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static S3Exception unsatisfiable(long size) {
        return new S3Exception(S3Error.INVALID_RANGE).with("ActualObjectSize", String.valueOf(size));
    }

    static int parsePartNumber(String v) {
        try {
            int n = Integer.parseInt(v);
            if (n < 1 || n > Http.MAX_PART_NUMBER) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new S3Exception(S3Error.INVALID_ARGUMENT, "Part number must be an integer between 1 and 10000, inclusive")
                    .with("ArgumentName", "partNumber").with("ArgumentValue", v);
        }
    }

    static void checksumHeaders(HttpServletResponse res, ObjectMeta m) {
        ChecksumAlgorithm alg = ChecksumAlgorithm.fromApiName(m.checksumAlgorithm());
        if (alg == null || m.checksum() == null) return;
        res.setHeader(alg.header(), m.checksum());
        res.setHeader("x-amz-checksum-type", m.checksumType());
    }

    static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }
}
