package io.github.simpan.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code simpan.*}. The quickest setup is two environment variables, SIMPAN_ACCESS_KEY and
 * SIMPAN_SECRET_KEY; more key pairs can be listed under simpan.credentials.
 */
@ConfigurationProperties(prefix = "simpan")
public class SimpanProperties {

    /** Where objects and metadata live. */
    private Path dataDir = Path.of("data");
    /** Region clients must sign for. */
    private String region = "us-east-1";
    /** Base domain for virtual-hosted-style requests (bucket.domain); path style always works. */
    private String domain;
    /** Flush data and metadata to disk before acknowledging writes. Turn off only for tests. */
    private boolean fsync = true;
    /** Compact the metadata log into a snapshot after this many records. */
    private long compactAfterRecords = 100_000;
    private String accessKey;
    private String secretKey;
    private List<Credential> credentials = new ArrayList<>();

    public static class Credential {
        private String accessKey;
        private String secretKey;

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }
    }

    public Path getDataDir() {
        return dataDir;
    }

    public void setDataDir(Path dataDir) {
        this.dataDir = dataDir;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public boolean isFsync() {
        return fsync;
    }

    public void setFsync(boolean fsync) {
        this.fsync = fsync;
    }

    public long getCompactAfterRecords() {
        return compactAfterRecords;
    }

    public void setCompactAfterRecords(long compactAfterRecords) {
        this.compactAfterRecords = compactAfterRecords;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public List<Credential> getCredentials() {
        return credentials;
    }

    public void setCredentials(List<Credential> credentials) {
        this.credentials = credentials;
    }
}
