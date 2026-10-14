package io.github.simpan.storage;

/**
 * Conditional write: {@code ifNoneMatchAny} for If-None-Match: * (create only if absent), {@code ifMatch} for
 * If-Match: "etag" (replace only that version). Checked atomically with the write.
 */
public record Precondition(boolean ifNoneMatchAny, String ifMatch) {
    public static final Precondition NONE = new Precondition(false, null);

    public boolean isNone() {
        return !ifNoneMatchAny && ifMatch == null;
    }
}
