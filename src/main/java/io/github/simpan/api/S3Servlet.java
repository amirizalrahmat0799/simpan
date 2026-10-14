package io.github.simpan.api;

import io.github.simpan.auth.SigV4Verifier;
import io.github.simpan.metrics.S3Metrics;
import io.github.simpan.storage.Storage;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The S3 endpoint. S3 routes by HTTP method, by whether the path names a bucket or an object, and by query
 * parameters (?uploads, ?uploadId, ?list-type=2, ?delete...), and it needs the raw, undecoded path to verify
 * signatures, so it is a plain servlet rather than Spring MVC controllers.
 */
public final class S3Servlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(S3Servlet.class);

    /** Sub-resources that exist in S3 but are not implemented here; requests for them get NotImplemented. */
    private static final Set<String> UNSUPPORTED = Set.of("policy", "cors", "lifecycle", "tagging", "website", "logging",
            "notification", "replication", "encryption", "object-lock", "retention", "legal-hold", "versions",
            "analytics", "inventory", "metrics", "intelligent-tiering", "ownershipControls", "publicAccessBlock",
            "requestPayment", "accelerate", "restore", "select", "torrent", "attributes", "policyStatus", "versionId");

    private final transient Storage storage;
    private final transient SigV4Verifier verifier;
    private final transient Clock clock;
    private final transient S3Metrics metrics;
    private final transient BucketHandler buckets;
    private final transient ObjectHandler objects;
    private final transient MultipartHandler multipart;
    private final String domain;

    public S3Servlet(Storage storage, SigV4Verifier verifier, Clock clock, S3Metrics metrics, String region,
            String domain) {
        this.storage = storage;
        this.verifier = verifier;
        this.clock = clock;
        this.metrics = metrics;
        this.domain = domain;
        UploadReader uploads = new UploadReader(storage.blobs());
        this.buckets = new BucketHandler(storage, uploads, region);
        this.objects = new ObjectHandler(storage, uploads, metrics);
        this.multipart = new MultipartHandler(storage, uploads, metrics);
    }

    @Override
    protected void service(HttpServletRequest http, HttpServletResponse res) throws IOException {
        long started = System.nanoTime();
        String requestId = HexFormat.of().withUpperCase().formatHex(longBytes(ThreadLocalRandom.current().nextLong()));
        res.setHeader("x-amz-request-id", requestId);
        res.setHeader("x-amz-id-2", requestId);
        res.setHeader("Server", "simpan");
        S3Request req = null;
        String operation = "Unknown";
        try {
            req = new S3Request(http, domain);
            operation = operation(req);
            req.auth(verifier.verify(req, clock.instant()));
            dispatch(operation, req, res);
        } catch (S3Exception e) {
            writeError(http, res, req, e, requestId);
        } catch (IOException e) {
            log.debug("I/O error during {}: {}", operation, e.toString());
            if (!res.isCommitted()) writeError(http, res, req, new S3Exception(S3Error.INCOMPLETE_BODY), requestId);
        } catch (RuntimeException e) {
            log.error("Internal error during {} {} (request {})", operation, http.getRequestURI(), requestId, e);
            writeError(http, res, req, new S3Exception(S3Error.INTERNAL_ERROR), requestId);
        } finally {
            metrics.request(operation, res.getStatus(), Duration.ofNanos(System.nanoTime() - started));
            if (log.isDebugEnabled()) {
                log.debug("{} {} {} -> {} ({} ms)", operation, http.getMethod(), http.getRequestURI(), res.getStatus(),
                        (System.nanoTime() - started) / 1_000_000);
            }
        }
    }

    /** Names the S3 operation a request is for (used for routing and metrics). */
    static String operation(S3Request r) {
        String m = r.method();
        if (r.bucket() == null) return m.equals("GET") ? "ListBuckets" : "MethodNotAllowed";
        for (String p : r.params().keySet()) {
            if (UNSUPPORTED.contains(p)) return "NotImplemented";
        }
        if (r.key() == null) {
            return switch (m) {
                case "PUT" -> r.has("acl") || r.has("versioning") ? "NotImplemented" : "CreateBucket";
                case "HEAD" -> "HeadBucket";
                case "DELETE" -> "DeleteBucket";
                case "POST" -> r.has("delete") ? "DeleteObjects" : "NotImplemented";
                case "GET" -> r.has("location") ? "GetBucketLocation"
                        : r.has("versioning") ? "GetBucketVersioning"
                        : r.has("acl") ? "GetBucketAcl"
                        : r.has("uploads") ? "ListMultipartUploads"
                        : "2".equals(r.param("list-type")) ? "ListObjectsV2" : "ListObjects";
                default -> "MethodNotAllowed";
            };
        }
        boolean copy = r.header("x-amz-copy-source") != null;
        return switch (m) {
            case "PUT" -> r.has("acl") ? "NotImplemented"
                    : r.has("uploadId") ? (copy ? "UploadPartCopy" : "UploadPart")
                    : copy ? "CopyObject" : "PutObject";
            case "GET" -> r.has("uploadId") ? "ListParts" : r.has("acl") ? "GetObjectAcl" : "GetObject";
            case "HEAD" -> "HeadObject";
            case "DELETE" -> r.has("uploadId") ? "AbortMultipartUpload" : "DeleteObject";
            case "POST" -> r.has("uploads") ? "CreateMultipartUpload"
                    : r.has("uploadId") ? "CompleteMultipartUpload" : "NotImplemented";
            default -> "MethodNotAllowed";
        };
    }

    private void dispatch(String operation, S3Request req, HttpServletResponse res) throws IOException {
        switch (operation) {
            case "ListBuckets" -> buckets.listBuckets(req, res);
            case "CreateBucket" -> buckets.create(req, res);
            case "HeadBucket" -> buckets.head(req, res);
            case "DeleteBucket" -> buckets.delete(req, res);
            case "GetBucketLocation" -> buckets.location(req, res);
            case "GetBucketVersioning" -> buckets.versioning(req, res);
            case "GetBucketAcl", "GetObjectAcl" -> buckets.acl(req, res);
            case "ListObjects", "ListObjectsV2" -> buckets.listObjects(req, res);
            case "DeleteObjects" -> buckets.deleteObjects(req, res);
            case "ListMultipartUploads" -> multipart.listUploads(req, res);
            case "PutObject" -> objects.put(req, res);
            case "CopyObject" -> objects.copy(req, res);
            case "GetObject" -> objects.get(req, res);
            case "HeadObject" -> objects.head(req, res);
            case "DeleteObject" -> objects.delete(req, res);
            case "CreateMultipartUpload" -> multipart.create(req, res);
            case "UploadPart" -> multipart.uploadPart(req, res);
            case "UploadPartCopy" -> multipart.uploadPartCopy(req, res);
            case "CompleteMultipartUpload" -> multipart.complete(req, res);
            case "AbortMultipartUpload" -> multipart.abort(req, res);
            case "ListParts" -> multipart.listParts(req, res);
            case "NotImplemented" -> throw new S3Exception(S3Error.NOT_IMPLEMENTED);
            default -> throw new S3Exception(S3Error.METHOD_NOT_ALLOWED).with("Method", req.method());
        }
    }

    private static void writeError(HttpServletRequest http, HttpServletResponse res, S3Request req, S3Exception e,
            String requestId) throws IOException {
        if (res.isCommitted()) {
            log.warn("{} after the response started; closing the connection", e.error().code());
            return;
        }
        res.reset();
        res.setHeader("x-amz-request-id", requestId);
        res.setHeader("x-amz-id-2", requestId);
        res.setHeader("Server", "simpan");
        if (e.error() == S3Error.INVALID_RANGE && e.fields().containsKey("ActualObjectSize")) {
            res.setHeader("Content-Range", "bytes */" + e.fields().get("ActualObjectSize"));
        }
        if ("HEAD".equals(http.getMethod())) {
            res.setStatus(e.error().status());
            return;
        }
        Xml x = new Xml().rootNoNs("Error").el("Code", e.error().code()).el("Message", e.getMessage());
        for (Map.Entry<String, String> f : e.fields().entrySet()) x.el(f.getKey(), f.getValue());
        if (req != null && req.bucket() != null && !e.fields().containsKey("BucketName")) x.el("BucketName", req.bucket());
        if (req != null) x.el("Resource", req.resource());
        x.el("RequestId", requestId).el("HostId", requestId).close("Error");
        Http.xml(res, e.error().status(), x);
    }

    private static byte[] longBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) v;
            v >>>= 8;
        }
        return b;
    }

    Storage storage() {
        return storage;
    }
}
