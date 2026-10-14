package io.github.simpan.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** A minimal streaming XML writer for S3 response documents. */
public final class Xml {

    public static final String NS = "http://s3.amazonaws.com/doc/2006-03-01/";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final StringBuilder sb = new StringBuilder(1024);

    public Xml() {
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    }

    /** Opens the root element with the S3 namespace. */
    public Xml root(String name) {
        sb.append('<').append(name).append(" xmlns=\"").append(NS).append("\">");
        return this;
    }

    public Xml rootNoNs(String name) {
        sb.append('<').append(name).append('>');
        return this;
    }

    public Xml open(String name) {
        sb.append('<').append(name).append('>');
        return this;
    }

    public Xml close(String name) {
        sb.append("</").append(name).append('>');
        return this;
    }

    /** {@code <name>value</name>}, skipped when value is null. */
    public Xml el(String name, Object value) {
        if (value == null) return this;
        sb.append('<').append(name).append('>');
        escape(String.valueOf(value));
        sb.append("</").append(name).append('>');
        return this;
    }

    /** Appends markup as is (for elements with attributes). */
    public Xml raw(String markup) {
        sb.append(markup);
        return this;
    }

    public Xml empty(String name) {
        sb.append('<').append(name).append("/>");
        return this;
    }

    public Xml time(String name, long epochMillis) {
        return el(name, ISO.format(Instant.ofEpochMilli(epochMillis)));
    }

    public static String iso(long epochMillis) {
        return ISO.format(Instant.ofEpochMilli(epochMillis));
    }

    private void escape(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> {
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                        sb.append("&#").append((int) c).append(';');
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }

    public byte[] bytes() {
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
