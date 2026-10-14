package io.github.simpan.api;

import io.github.simpan.io.ChecksumAlgorithm;
import io.github.simpan.metrics.S3Metrics;
import io.github.simpan.storage.KeyOrder;
import io.github.simpan.storage.MultipartUpload;
import io.github.simpan.storage.ObjectMeta;
import io.github.simpan.storage.PartMeta;
import io.github.simpan.storage.Precondition;
import io.github.simpan.storage.StagedBlob;
import io.github.simpan.storage.Storage;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.w3c.dom.Element;

/** Multipart upload operations. */
final class MultipartHandler {

    private final Storage storage;
    private final UploadReader uploads;
    private final S3Metrics metrics;

    MultipartHandler(Storage storage, UploadReader uploads, S3Metrics metrics) {
        this.storage = storage;
        this.uploads = uploads;
        this.metrics = metrics;
    }

    void create(S3Request req, HttpServletResponse res) throws IOException {
        Http.validateKey(req.key());
        String algName = req.header("x-amz-checksum-algorithm");
        ChecksumAlgorithm alg = ChecksumAlgorithm.CRC64NVME;
        if (algName != null) {
            alg = ChecksumAlgorithm.fromApiName(algName);
            if (alg == null) throw new S3Exception(S3Error.INVALID_REQUEST, "Checksum algorithm provided is unsupported.");
        }
        String type = req.header("x-amz-checksum-type");
        if (type == null) {
            type = alg == ChecksumAlgorithm.CRC64NVME ? "FULL_OBJECT" : "COMPOSITE";
        } else {
            type = type.trim().toUpperCase(Locale.ROOT);
            if (!type.equals("FULL_OBJECT") && !type.equals("COMPOSITE")) {
                throw new S3Exception(S3Error.INVALID_REQUEST, "Value for x-amz-checksum-type header is invalid.");
            }
            if (type.equals("FULL_OBJECT") && !alg.supportsFullObject()) {
                throw new S3Exception(S3Error.INVALID_REQUEST, "The " + alg.apiName()
                        + " checksum algorithm only supports the COMPOSITE checksum type.");
            }
            if (type.equals("COMPOSITE") && alg == ChecksumAlgorithm.CRC64NVME) {
                throw new S3Exception(S3Error.INVALID_REQUEST, "The CRC64NVME checksum algorithm only supports full object checksums.");
            }
        }
        MultipartUpload u = storage.createUpload(req.bucket(), req.key(), Attributes.from(req.http()), alg, type);
        if (algName != null) {
            res.setHeader("x-amz-checksum-algorithm", alg.apiName());
            res.setHeader("x-amz-checksum-type", type);
        }
        Http.xml(res, 200, new Xml().root("InitiateMultipartUploadResult").el("Bucket", u.bucket()).el("Key", u.key())
                .el("UploadId", u.uploadId()).close("InitiateMultipartUploadResult"));
    }

    void uploadPart(S3Request req, HttpServletResponse res) throws IOException {
        MultipartUpload u = storage.upload(req.param("uploadId"), req.bucket(), req.key());
        int partNumber = ObjectHandler.parsePartNumber(req.param("partNumber"));
        ChecksumAlgorithm alg = ChecksumAlgorithm.fromApiName(u.checksumAlgorithm());
        UploadReader.Upload up = uploads.read(req, Http.MAX_PUT_SIZE, alg);
        metrics.received(up.staged().size());
        PartMeta p = storage.putPart(u, partNumber, up.staged(), up.checksum());
        res.setHeader("ETag", p.etag());
        if (up.clientAlgorithm() != null && up.clientChecksum() != null) {
            res.setHeader(up.clientAlgorithm().header(), ObjectHandler.b64(up.clientChecksum()));
        } else {
            res.setHeader(alg.header(), p.checksum());
        }
        res.setStatus(200);
    }

    void uploadPartCopy(S3Request req, HttpServletResponse res) throws IOException {
        MultipartUpload u = storage.upload(req.param("uploadId"), req.bucket(), req.key());
        int partNumber = ObjectHandler.parsePartNumber(req.param("partNumber"));
        ObjectMeta source = ObjectHandler.copySource(req, storage);
        long offset = 0;
        long length = source.size();
        String range = req.header("x-amz-copy-source-range");
        if (range != null) {
            long[] r = ObjectHandler.parseRange(range, source.size());
            if (r == null) throw new S3Exception(S3Error.INVALID_ARGUMENT, "The x-amz-copy-source-range value must be of the form bytes=first-last");
            offset = r[0];
            length = r[1] - r[0] + 1;
        }
        ChecksumAlgorithm alg = ChecksumAlgorithm.fromApiName(u.checksumAlgorithm());
        ChecksumAlgorithm.Hasher h = alg.newHasher();
        StagedBlob staged = storage.blobs().stageCopy(source.segments(), offset, length, List.of(h));
        PartMeta p = storage.putPart(u, partNumber, staged, h.digest());
        Http.xml(res, 200, new Xml().root("CopyPartResult").el("ETag", p.etag()).time("LastModified", p.lastModified())
                .el(alg.xmlElement(), p.checksum()).close("CopyPartResult"));
    }

    void complete(S3Request req, HttpServletResponse res) throws IOException {
        MultipartUpload u = storage.upload(req.param("uploadId"), req.bucket(), req.key());
        Element root = XmlInput.parse(uploads.readSmall(req, 4 * 1024 * 1024));
        if (!"CompleteMultipartUpload".equals(XmlInput.name(root))) throw new S3Exception(S3Error.MALFORMED_XML);
        List<Element> requested = XmlInput.children(root, "Part");
        if (requested.isEmpty()) {
            throw new S3Exception(S3Error.MALFORMED_XML, "You must specify at least one part");
        }
        ChecksumAlgorithm alg = ChecksumAlgorithm.fromApiName(u.checksumAlgorithm());
        List<PartMeta> chosen = new ArrayList<>();
        int previous = 0;
        for (Element e : requested) {
            int n;
            try {
                n = Integer.parseInt(XmlInput.text(e, "PartNumber").trim());
            } catch (RuntimeException ex) {
                throw new S3Exception(S3Error.MALFORMED_XML);
            }
            if (n <= previous) throw new S3Exception(S3Error.INVALID_PART_ORDER);
            previous = n;
            PartMeta p = storage.part(u, n);
            String etag = XmlInput.text(e, "ETag");
            if (p == null || etag == null || !Storage.etagMatches(etag, p.etag())) {
                throw new S3Exception(S3Error.INVALID_PART).with("UploadId", u.uploadId()).with("PartNumber", String.valueOf(n));
            }
            String claimed = XmlInput.text(e, alg.xmlElement());
            if (claimed != null && !claimed.trim().equals(p.checksum())) {
                throw new S3Exception(S3Error.INVALID_PART, "The " + alg.apiName() + " checksum of part " + n
                        + " does not match the uploaded part").with("PartNumber", String.valueOf(n));
            }
            chosen.add(p);
        }
        for (int i = 0; i < chosen.size() - 1; i++) {
            if (chosen.get(i).size() < Http.MIN_PART_SIZE) {
                throw new S3Exception(S3Error.ENTITY_TOO_SMALL).with("PartNumber", String.valueOf(chosen.get(i).partNumber()))
                        .with("ProposedSize", String.valueOf(chosen.get(i).size()))
                        .with("MinSizeAllowed", String.valueOf(Http.MIN_PART_SIZE));
            }
        }
        Precondition pre = ObjectHandler.writePrecondition(req);
        ObjectMeta m = storage.completeUpload(u, chosen, pre);
        String host = req.header("host");
        Xml x = new Xml().root("CompleteMultipartUploadResult")
                .el("Location", "http://" + host + "/" + u.bucket() + "/" + u.key())
                .el("Bucket", u.bucket()).el("Key", u.key()).el("ETag", m.etag())
                .el(alg.xmlElement(), m.checksum()).el("ChecksumType", m.checksumType())
                .close("CompleteMultipartUploadResult");
        Http.xml(res, 200, x);
    }

    void abort(S3Request req, HttpServletResponse res) {
        MultipartUpload u = storage.upload(req.param("uploadId"), req.bucket(), req.key());
        storage.abortUpload(u.uploadId());
        res.setStatus(204);
    }

    void listParts(S3Request req, HttpServletResponse res) throws IOException {
        MultipartUpload u = storage.upload(req.param("uploadId"), req.bucket(), req.key());
        int max = intParam(req.param("max-parts"), 1000, "max-parts", 1000);
        int marker = intParam(req.param("part-number-marker"), 0, "part-number-marker", Http.MAX_PART_NUMBER);
        ChecksumAlgorithm alg = ChecksumAlgorithm.fromApiName(u.checksumAlgorithm());
        List<PartMeta> page = new ArrayList<>();
        boolean truncated = false;
        for (PartMeta p : storage.parts(u)) {
            if (p.partNumber() <= marker) continue;
            if (page.size() == max) {
                truncated = true;
                break;
            }
            page.add(p);
        }
        Xml x = new Xml().root("ListPartsResult").el("Bucket", u.bucket()).el("Key", u.key()).el("UploadId", u.uploadId());
        x.el("PartNumberMarker", marker);
        if (!page.isEmpty()) x.el("NextPartNumberMarker", page.get(page.size() - 1).partNumber());
        x.el("MaxParts", max).el("IsTruncated", truncated);
        for (PartMeta p : page) {
            x.open("Part").el("PartNumber", p.partNumber()).time("LastModified", p.lastModified()).el("ETag", p.etag())
                    .el("Size", p.size()).el(alg.xmlElement(), p.checksum()).close("Part");
        }
        owner(x, "Initiator");
        owner(x, "Owner");
        x.el("StorageClass", "STANDARD").el("ChecksumAlgorithm", alg.apiName()).el("ChecksumType", u.checksumType());
        x.close("ListPartsResult");
        Http.xml(res, 200, x);
    }

    void listUploads(S3Request req, HttpServletResponse res) throws IOException {
        String prefix = req.param("prefix") == null ? "" : req.param("prefix");
        String keyMarker = req.param("key-marker");
        String idMarker = req.param("upload-id-marker");
        int max = intParam(req.param("max-uploads"), 1000, "max-uploads", 1000);
        List<MultipartUpload> page = new ArrayList<>();
        boolean truncated = false;
        for (MultipartUpload u : storage.uploads(req.bucket())) {
            if (!u.key().startsWith(prefix)) continue;
            if (keyMarker != null) {
                int c = KeyOrder.INSTANCE.compare(u.key(), keyMarker);
                if (c < 0 || (c == 0 && (idMarker == null || u.uploadId().compareTo(idMarker) <= 0))) continue;
            }
            if (page.size() == max) {
                truncated = true;
                break;
            }
            page.add(u);
        }
        Xml x = new Xml().root("ListMultipartUploadsResult").el("Bucket", req.bucket())
                .el("KeyMarker", keyMarker == null ? "" : keyMarker).el("UploadIdMarker", idMarker == null ? "" : idMarker);
        if (truncated && !page.isEmpty()) {
            MultipartUpload last = page.get(page.size() - 1);
            x.el("NextKeyMarker", last.key()).el("NextUploadIdMarker", last.uploadId());
        }
        x.el("Prefix", prefix).el("MaxUploads", max).el("IsTruncated", truncated);
        for (MultipartUpload u : page) {
            x.open("Upload").el("Key", u.key()).el("UploadId", u.uploadId());
            owner(x, "Initiator");
            owner(x, "Owner");
            x.el("StorageClass", "STANDARD").time("Initiated", u.initiated()).el("ChecksumAlgorithm", u.checksumAlgorithm())
                    .el("ChecksumType", u.checksumType()).close("Upload");
        }
        x.close("ListMultipartUploadsResult");
        Http.xml(res, 200, x);
    }

    private static void owner(Xml x, String element) {
        x.open(element).el("ID", Http.OWNER_ID).el("DisplayName", Http.OWNER_NAME).close(element);
    }

    private static int intParam(String v, int dflt, String name, int cap) {
        if (v == null) return dflt;
        try {
            int n = Integer.parseInt(v);
            if (n < 0) throw new NumberFormatException();
            return Math.min(n, cap);
        } catch (NumberFormatException e) {
            throw new S3Exception(S3Error.INVALID_ARGUMENT, "Invalid " + name).with("ArgumentName", name).with("ArgumentValue", v);
        }
    }
}
