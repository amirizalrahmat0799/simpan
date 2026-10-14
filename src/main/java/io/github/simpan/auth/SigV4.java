package io.github.simpan.auth;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** AWS Signature Version 4 primitives. */
public final class SigV4 {

    public static final String ALGORITHM = "AWS4-HMAC-SHA256";
    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    public static final String STREAMING_SIGNED = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD";
    public static final String STREAMING_SIGNED_TRAILER = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER";
    public static final String STREAMING_UNSIGNED_TRAILER = "STREAMING-UNSIGNED-PAYLOAD-TRAILER";
    public static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final HexFormat HEX = HexFormat.of();

    private SigV4() {}

    public static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hmacHex(byte[] key, String data) {
        return HEX.formatHex(hmac(key, data));
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String data) {
        return HEX.formatHex(sha256().digest(data.getBytes(StandardCharsets.UTF_8)));
    }

    public static String hex(byte[] b) {
        return HEX.formatHex(b);
    }

    /** kSigning = HMAC(HMAC(HMAC(HMAC("AWS4" + secret, date), region), service), "aws4_request"). */
    public static byte[] signingKey(String secret, String date, String region, String service) {
        byte[] k = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), date);
        k = hmac(k, region);
        k = hmac(k, service);
        return hmac(k, "aws4_request");
    }

    /**
     * URI-encodes per SigV4: every byte except A-Z a-z 0-9 - _ . ~ becomes %XX (uppercase), spaces become %20, and
     * '/' is kept when {@code keepSlash} (object key paths) or encoded (query strings).
     */
    public static String uriEncode(String s, boolean keepSlash) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + 16);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_'
                    || c == '.' || c == '~' || (keepSlash && c == '/')) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    /**
     * Decodes %XX escapes to UTF-8. A '+' stays a '+': S3 clients encode spaces as %20, and treating '+' as a
     * space would corrupt keys that contain a plus sign.
     */
    public static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                if (Character.isHighSurrogate(c) && i + 1 < s.length()) {
                    b = s.substring(i, i + 2).getBytes(StandardCharsets.UTF_8);
                    i++;
                }
                out.writeBytes(b);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    /** Collapses runs of spaces and trims, as canonical header values require. */
    public static String canonicalValue(String v) {
        return v.trim().replaceAll(" +", " ");
    }

    public static String stringToSign(String amzDate, String scope, String canonicalRequest) {
        return ALGORITHM + "\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest);
    }
}
