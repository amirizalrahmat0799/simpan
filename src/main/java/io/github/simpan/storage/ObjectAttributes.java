package io.github.simpan.storage;

import java.util.Map;

/** What the client sets on an object at upload: content headers and user metadata. */
public record ObjectAttributes(Map<String, String> headers, Map<String, String> userMetadata) {
    public ObjectAttributes {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        userMetadata = userMetadata == null ? Map.of() : Map.copyOf(userMetadata);
    }

    public static ObjectAttributes empty() {
        return new ObjectAttributes(Map.of(), Map.of());
    }
}
