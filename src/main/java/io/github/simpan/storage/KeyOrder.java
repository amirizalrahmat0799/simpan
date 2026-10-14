package io.github.simpan.storage;

import java.util.Comparator;

/**
 * Orders keys by Unicode code point, which is the same as S3's UTF-8 binary order. Java's String.compareTo uses
 * UTF-16 code units and would put characters above U+FFFF (emoji, for example) before U+E000-U+FFFF.
 */
public final class KeyOrder implements Comparator<String> {

    public static final KeyOrder INSTANCE = new KeyOrder();

    private KeyOrder() {}

    @Override
    public int compare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) return Integer.compare(ca, cb);
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /** The smallest string greater than every string that starts with {@code prefix}, or null if none exists. */
    public static String prefixEnd(String prefix) {
        int[] cps = prefix.codePoints().toArray();
        for (int i = cps.length - 1; i >= 0; i--) {
            if (cps[i] < Character.MAX_CODE_POINT) {
                int next = cps[i] + 1;
                if (next == Character.MIN_SURROGATE) next = Character.MAX_SURROGATE + 1;
                return new String(cps, 0, i) + new String(Character.toChars(next));
            }
        }
        return null;
    }
}
