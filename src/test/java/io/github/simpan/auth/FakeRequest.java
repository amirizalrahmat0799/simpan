package io.github.simpan.auth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A request for verifier tests, built from AWS documentation examples. */
public record FakeRequest(String method, String rawPath, String rawQuery, Map<String, List<String>> headerMap)
        implements SignableRequest, SigV2Verifier.HeaderNames {

    public static FakeRequest of(String method, String path, String query, String... headers) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i += 2) {
            m.computeIfAbsent(headers[i].toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(headers[i + 1]);
        }
        return new FakeRequest(method, path, query, m);
    }

    @Override
    public List<String> headers(String name) {
        return headerMap.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    @Override
    public List<String> headerNames() {
        return new ArrayList<>(headerMap.keySet());
    }
}
