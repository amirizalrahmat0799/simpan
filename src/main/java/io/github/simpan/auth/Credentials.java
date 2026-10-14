package io.github.simpan.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Access keys accepted by the server. */
public final class Credentials {
    private final Map<String, String> secrets = new ConcurrentHashMap<>();

    public Credentials add(String accessKey, String secretKey) {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.length() < 8) {
            throw new IllegalArgumentException("Access key required and secret key must be at least 8 characters");
        }
        secrets.put(accessKey, secretKey);
        return this;
    }

    public String secretFor(String accessKey) {
        return secrets.get(accessKey);
    }

    public boolean isEmpty() {
        return secrets.isEmpty();
    }
}
