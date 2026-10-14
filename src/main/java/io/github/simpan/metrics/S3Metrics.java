package io.github.simpan.metrics;

import io.github.simpan.storage.Storage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/** Prometheus metrics: requests by S3 operation and status, bytes in and out, objects and bytes stored. */
public final class S3Metrics {

    private final MeterRegistry registry;
    private final Counter received;
    private final Counter sent;

    public S3Metrics(MeterRegistry registry, Storage storage) {
        this.registry = registry;
        this.received = Counter.builder("simpan.bytes.received").description("Object bytes uploaded").baseUnit("bytes")
                .register(registry);
        this.sent = Counter.builder("simpan.bytes.sent").description("Object bytes downloaded").baseUnit("bytes")
                .register(registry);
        Gauge.builder("simpan.objects", storage, Storage::objectCount).description("Objects stored").register(registry);
        Gauge.builder("simpan.stored.bytes", storage, Storage::bytesStored).description("Bytes stored").baseUnit("bytes")
                .register(registry);
    }

    public void request(String operation, int status, Duration took) {
        Timer.builder("simpan.requests").description("S3 API requests")
                .tag("operation", operation).tag("status", String.valueOf(status))
                .publishPercentileHistogram()
                .register(registry).record(took);
    }

    public void received(long bytes) {
        received.increment(bytes);
    }

    public void sent(long bytes) {
        sent.increment(bytes);
    }
}
