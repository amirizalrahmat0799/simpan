package io.github.simpan.api;

import io.github.simpan.auth.SigV4;
import io.github.simpan.storage.BucketInfo;
import io.github.simpan.storage.ListPage;
import io.github.simpan.storage.ObjectMeta;
import io.github.simpan.storage.Storage;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.w3c.dom.Element;

/** Bucket-level operations: create, delete, head, list objects, batch delete. */
final class BucketHandler {

    private final Storage storage;
    private final UploadReader uploads;
    private final String region;

    BucketHandler(Storage storage, UploadReader uploads, String region) {
        this.storage = storage;
        this.uploads = uploads;
        this.region = region;
    }

    void listBuckets(S3Request req, HttpServletResponse res) throws IOException {
        String prefix = req.param("prefix");
        Xml x = new Xml().root("ListAllMyBucketsResult");
        x.open("Owner").el("ID", Http.OWNER_ID).el("DisplayName", Http.OWNER_NAME).close("Owner");
        x.open("Buckets");
        for (BucketInfo b : storage.buckets()) {
            if (prefix != null && !b.name().startsWith(prefix)) continue;
            x.open("Bucket").el("Name", b.name()).time("CreationDate", b.createdAt()).el("BucketRegion", region)
                    .close("Bucket");
        }
        x.close("Buckets");
        if (prefix != null) x.el("Prefix", prefix);
        x.close("ListAllMyBucketsResult");
        Http.xml(res, 200, x);
    }

    void create(S3Request req, HttpServletResponse res) throws IOException {
        Http.validateBucketName(req.bucket());
        if (req.contentLength() > 0 || req.header("transfer-encoding") != null || req.auth().streaming()) {
            byte[] body = uploads.readSmall(req, 64 * 1024);
            if (body.length > 0) {
                Element root = XmlInput.parse(body);
                String constraint = XmlInput.text(root, "LocationConstraint");
                if (constraint != null && !constraint.isBlank() && !constraint.equals(region)) {
                    throw new S3Exception(S3Error.INVALID_REQUEST, "The specified location-constraint is not valid: "
                            + constraint + " (this server's region is " + region + ")");
                }
            }
        }
        storage.createBucket(req.bucket());
        res.setHeader("Location", "/" + req.bucket());
        res.setStatus(200);
    }

    void head(S3Request req, HttpServletResponse res) {
        if (storage.bucket(req.bucket()) == null) throw new S3Exception(S3Error.NO_SUCH_BUCKET).with("BucketName", req.bucket());
        res.setHeader("x-amz-bucket-region", region);
        res.setStatus(200);
    }

    void delete(S3Request req, HttpServletResponse res) {
        storage.deleteBucket(req.bucket());
        res.setStatus(204);
    }

    void location(S3Request req, HttpServletResponse res) throws IOException {
        requireBucket(req);
        // us-east-1 is reported as an empty constraint, as Amazon S3 does
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<LocationConstraint xmlns=\"" + Xml.NS + "\">"
                + ("us-east-1".equals(region) ? "" : region) + "</LocationConstraint>";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        res.setStatus(200);
        res.setContentType("application/xml");
        res.setContentLength(b.length);
        res.getOutputStream().write(b);
    }

    void versioning(S3Request req, HttpServletResponse res) throws IOException {
        requireBucket(req);
        Http.xml(res, 200, new Xml().root("VersioningConfiguration").close("VersioningConfiguration"));
    }

    void acl(S3Request req, HttpServletResponse res) throws IOException {
        if (req.key() == null) requireBucket(req);
        else if (storage.get(req.bucket(), req.key()) == null) throw new S3Exception(S3Error.NO_SUCH_KEY).with("Key", req.key());
        Xml x = new Xml().root("AccessControlPolicy");
        x.open("Owner").el("ID", Http.OWNER_ID).el("DisplayName", Http.OWNER_NAME).close("Owner");
        x.open("AccessControlList").open("Grant");
        x.raw("<Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"CanonicalUser\">");
        x.el("ID", Http.OWNER_ID).el("DisplayName", Http.OWNER_NAME).close("Grantee");
        x.el("Permission", "FULL_CONTROL").close("Grant").close("AccessControlList").close("AccessControlPolicy");
        Http.xml(res, 200, x);
    }

    void listObjects(S3Request req, HttpServletResponse res) throws IOException {
        requireBucket(req);
        boolean v2 = "2".equals(req.param("list-type"));
        String prefix = req.param("prefix");
        String delimiter = req.param("delimiter");
        boolean urlEncode = "url".equals(req.param("encoding-type"));
        if (req.param("encoding-type") != null && !urlEncode) {
            throw new S3Exception(S3Error.INVALID_ARGUMENT, "Invalid Encoding Method specified in Request");
        }
        int maxKeys = maxKeys(req.param("max-keys"));

        String after = null;
        boolean afterIsPrefix = false;
        String token = req.param("continuation-token");
        String startAfter = req.param("start-after");
        String marker = req.param("marker");
        if (v2 && token != null) {
            String decoded = decodeToken(token);
            afterIsPrefix = decoded.charAt(0) == 'p';
            after = decoded.substring(1);
        } else if (v2 && startAfter != null && !startAfter.isEmpty()) {
            after = startAfter;
        } else if (!v2 && marker != null && !marker.isEmpty()) {
            after = marker;
        }

        ListPage page = storage.list(req.bucket(), prefix, delimiter, after, afterIsPrefix, maxKeys);
        boolean fetchOwner = v2 ? "true".equals(req.param("fetch-owner")) : true;

        Xml x = new Xml().root("ListBucketResult");
        x.el("Name", req.bucket());
        x.el("Prefix", enc(prefix == null ? "" : prefix, urlEncode));
        if (v2) {
            if (token != null) x.el("ContinuationToken", token);
            if (startAfter != null) x.el("StartAfter", enc(startAfter, urlEncode));
        } else {
            x.el("Marker", enc(marker == null ? "" : marker, urlEncode));
            if (page.truncated() && page.last() != null) x.el("NextMarker", enc(page.last(), urlEncode));
        }
        x.el("MaxKeys", maxKeys);
        if (delimiter != null) x.el("Delimiter", enc(delimiter, urlEncode));
        if (urlEncode) x.el("EncodingType", "url");
        if (v2) x.el("KeyCount", page.objects().size() + page.commonPrefixes().size());
        x.el("IsTruncated", page.truncated());
        if (v2 && page.truncated() && page.last() != null) {
            x.el("NextContinuationToken", encodeToken(page.last(), page.lastIsPrefix()));
        }
        for (ObjectMeta m : page.objects()) {
            x.open("Contents").el("Key", enc(m.key(), urlEncode)).time("LastModified", m.lastModified())
                    .el("ETag", m.etag());
            if (m.checksumAlgorithm() != null) {
                x.el("ChecksumAlgorithm", m.checksumAlgorithm()).el("ChecksumType", m.checksumType());
            }
            x.el("Size", m.size()).el("StorageClass", "STANDARD");
            if (fetchOwner) x.open("Owner").el("ID", Http.OWNER_ID).el("DisplayName", Http.OWNER_NAME).close("Owner");
            x.close("Contents");
        }
        for (String p : page.commonPrefixes()) x.open("CommonPrefixes").el("Prefix", enc(p, urlEncode)).close("CommonPrefixes");
        x.close("ListBucketResult");
        Http.xml(res, 200, x);
    }

    void deleteObjects(S3Request req, HttpServletResponse res) throws IOException {
        requireBucket(req);
        byte[] body = uploads.readSmall(req, 2 * 1024 * 1024);
        Element root = XmlInput.parse(body);
        if (!"Delete".equals(XmlInput.name(root))) throw new S3Exception(S3Error.MALFORMED_XML);
        boolean quiet = "true".equalsIgnoreCase(String.valueOf(XmlInput.text(root, "Quiet")).trim());
        var objects = XmlInput.children(root, "Object");
        if (objects.isEmpty() || objects.size() > 1000) throw new S3Exception(S3Error.MALFORMED_XML);
        Xml x = new Xml().root("DeleteResult");
        for (Element o : objects) {
            String key = XmlInput.text(o, "Key");
            if (key == null || key.isEmpty()) {
                x.open("Error").el("Key", "").el("Code", "UserKeyMustBeSpecified")
                        .el("Message", "The key must be specified").close("Error");
                continue;
            }
            try {
                storage.delete(req.bucket(), key);
                if (!quiet) x.open("Deleted").el("Key", key).close("Deleted");
            } catch (S3Exception e) {
                x.open("Error").el("Key", key).el("Code", e.error().code()).el("Message", e.getMessage()).close("Error");
            } catch (RuntimeException e) {
                x.open("Error").el("Key", key).el("Code", "InternalError").el("Message", "Could not delete").close("Error");
            }
        }
        x.close("DeleteResult");
        Http.xml(res, 200, x);
    }

    private void requireBucket(S3Request req) {
        if (storage.bucket(req.bucket()) == null) throw new S3Exception(S3Error.NO_SUCH_BUCKET).with("BucketName", req.bucket());
    }

    private static int maxKeys(String v) {
        if (v == null) return 1000;
        try {
            int n = Integer.parseInt(v);
            if (n < 0) throw new NumberFormatException();
            return Math.min(n, 1000);
        } catch (NumberFormatException e) {
            throw new S3Exception(S3Error.INVALID_ARGUMENT, "Provided max-keys not an integer or within integer range");
        }
    }

    private static String enc(String v, boolean url) {
        return url ? SigV4.uriEncode(v, true) : v;
    }

    static String encodeToken(String last, boolean isPrefix) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(((isPrefix ? "p" : "k") + last).getBytes(StandardCharsets.UTF_8));
    }

    static String decodeToken(String token) {
        try {
            String s = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (s.isEmpty() || (s.charAt(0) != 'p' && s.charAt(0) != 'k')) throw new IllegalArgumentException();
            return s;
        } catch (IllegalArgumentException e) {
            throw new S3Exception(S3Error.INVALID_ARGUMENT, "The continuation token provided is incorrect");
        }
    }
}
