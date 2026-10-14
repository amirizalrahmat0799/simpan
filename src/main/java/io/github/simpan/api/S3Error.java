package io.github.simpan.api;

/** S3 error codes, with the HTTP status and default message Amazon S3 uses for each. */
public enum S3Error {
    ACCESS_DENIED("AccessDenied", 403, "Access Denied"),
    AUTHORIZATION_HEADER_MALFORMED("AuthorizationHeaderMalformed", 400, "The authorization header is malformed"),
    AUTHORIZATION_QUERY_PARAMETERS_ERROR("AuthorizationQueryParametersError", 400, "Error parsing the X-Amz-Credential parameter"),
    BAD_DIGEST("BadDigest", 400, "The Content-MD5 or checksum value that you specified did not match what the server received."),
    BUCKET_ALREADY_OWNED_BY_YOU("BucketAlreadyOwnedByYou", 409, "Your previous request to create the named bucket succeeded and you already own it."),
    BUCKET_NOT_EMPTY("BucketNotEmpty", 409, "The bucket you tried to delete is not empty"),
    ENTITY_TOO_LARGE("EntityTooLarge", 400, "Your proposed upload exceeds the maximum allowed size"),
    ENTITY_TOO_SMALL("EntityTooSmall", 400, "Your proposed upload is smaller than the minimum allowed object size."),
    INCOMPLETE_BODY("IncompleteBody", 400, "You did not provide the number of bytes specified by the Content-Length HTTP header."),
    INTERNAL_ERROR("InternalError", 500, "We encountered an internal error. Please try again."),
    INVALID_ACCESS_KEY_ID("InvalidAccessKeyId", 403, "The AWS Access Key Id you provided does not exist in our records."),
    INVALID_ARGUMENT("InvalidArgument", 400, "Invalid Argument"),
    INVALID_BUCKET_NAME("InvalidBucketName", 400, "The specified bucket is not valid."),
    INVALID_DIGEST("InvalidDigest", 400, "The Content-MD5 or checksum value that you specified is not valid."),
    INVALID_PART("InvalidPart", 400, "One or more of the specified parts could not be found. The part may not have been uploaded, or the specified entity tag may not match the part's entity tag."),
    INVALID_PART_ORDER("InvalidPartOrder", 400, "The list of parts was not in ascending order. The parts list must be specified in order by part number."),
    INVALID_RANGE("InvalidRange", 416, "The requested range is not satisfiable"),
    INVALID_REQUEST("InvalidRequest", 400, "Invalid Request"),
    KEY_TOO_LONG("KeyTooLongError", 400, "Your key is too long"),
    MALFORMED_XML("MalformedXML", 400, "The XML you provided was not well-formed or did not validate against our published schema."),
    METADATA_TOO_LARGE("MetadataTooLarge", 400, "Your metadata headers exceed the maximum allowed metadata size."),
    METHOD_NOT_ALLOWED("MethodNotAllowed", 405, "The specified method is not allowed against this resource."),
    MISSING_CONTENT_LENGTH("MissingContentLength", 411, "You must provide the Content-Length HTTP header."),
    MISSING_SECURITY_HEADER("MissingSecurityHeader", 400, "Your request was missing a required header"),
    NO_SUCH_BUCKET("NoSuchBucket", 404, "The specified bucket does not exist"),
    NO_SUCH_KEY("NoSuchKey", 404, "The specified key does not exist."),
    NO_SUCH_UPLOAD("NoSuchUpload", 404, "The specified multipart upload does not exist. The upload ID may be invalid, or the upload may have been aborted or completed."),
    NOT_IMPLEMENTED("NotImplemented", 501, "A header or query you provided implies functionality that is not implemented."),
    PRECONDITION_FAILED("PreconditionFailed", 412, "At least one of the pre-conditions you specified did not hold"),
    REQUEST_TIME_TOO_SKEWED("RequestTimeTooSkewed", 403, "The difference between the request time and the server's time is too large."),
    SIGNATURE_DOES_NOT_MATCH("SignatureDoesNotMatch", 403, "The request signature we calculated does not match the signature you provided. Check your key and signing method."),
    CONTENT_SHA256_MISMATCH("XAmzContentSHA256Mismatch", 400, "The provided 'x-amz-content-sha256' header does not match what was computed."),
    CONDITIONAL_REQUEST_CONFLICT("ConditionalRequestConflict", 409, "A conflicting operation occurred. Please retry.");

    private final String code;
    private final int status;
    private final String message;

    S3Error(String code, int status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public String message() {
        return message;
    }
}
