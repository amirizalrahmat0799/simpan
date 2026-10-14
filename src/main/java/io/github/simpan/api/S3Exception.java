package io.github.simpan.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** An S3 error response. Extra fields become elements of the &lt;Error&gt; document (BucketName, Key, ...). */
public class S3Exception extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final S3Error error;
    private final transient Map<String, String> fields = new LinkedHashMap<>();

    public S3Exception(S3Error error) {
        this(error, error.message());
    }

    public S3Exception(S3Error error, String message) {
        super(message);
        this.error = error;
    }

    public S3Exception(S3Error error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public S3Error error() {
        return error;
    }

    public Map<String, String> fields() {
        return fields;
    }

    public S3Exception with(String field, String value) {
        if (value != null) fields.put(field, value);
        return this;
    }
}
