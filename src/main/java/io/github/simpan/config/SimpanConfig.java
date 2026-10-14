package io.github.simpan.config;

import io.github.simpan.api.S3Servlet;
import io.github.simpan.auth.Credentials;
import io.github.simpan.auth.SigV4Verifier;
import io.github.simpan.metrics.S3Metrics;
import io.github.simpan.storage.Storage;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SimpanProperties.class)
public class SimpanConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Credentials credentials(SimpanProperties props) {
        Credentials c = new Credentials();
        if (props.getAccessKey() != null && !props.getAccessKey().isBlank()) c.add(props.getAccessKey(), props.getSecretKey());
        for (SimpanProperties.Credential k : props.getCredentials()) c.add(k.getAccessKey(), k.getSecretKey());
        if (c.isEmpty()) {
            throw new IllegalStateException("No credentials configured. Set SIMPAN_ACCESS_KEY and SIMPAN_SECRET_KEY "
                    + "(secret at least 8 characters), or list simpan.credentials.");
        }
        return c;
    }

    @Bean
    SigV4Verifier verifier(Credentials credentials, SimpanProperties props) {
        return new SigV4Verifier(credentials, props.getRegion());
    }

    @Bean(destroyMethod = "close")
    Storage storage(SimpanProperties props, Clock clock) {
        Storage s = new Storage(props.getDataDir(), props.isFsync(), props.getCompactAfterRecords(), clock);
        s.open();
        return s;
    }

    @Bean
    S3Metrics s3Metrics(MeterRegistry registry, Storage storage) {
        return new S3Metrics(registry, storage);
    }

    /** Serves every path on the main port; actuator runs on the management port. */
    @Bean
    ServletRegistrationBean<S3Servlet> s3Servlet(Storage storage, SigV4Verifier verifier, Clock clock, S3Metrics metrics,
            SimpanProperties props) {
        ServletRegistrationBean<S3Servlet> reg = new ServletRegistrationBean<>(
                new S3Servlet(storage, verifier, clock, metrics, props.getRegion(), props.getDomain()), "/*");
        reg.setName("s3");
        reg.setLoadOnStartup(1);
        return reg;
    }

    /**
     * Jetty rather than Tomcat: Tomcat answers "Expect: 100-continue" with a status line that has no reason phrase
     * ("HTTP/1.1 100"), which botocore releases before mid-2025 (boto3, the AWS CLI v1) cannot parse, so every
     * upload from them failed. Jetty sends "HTTP/1.1 100 Continue".
     *
     * <p>S3 object keys can also contain sequences Jetty rejects by default as ambiguous ("%2F", "//", ";"); the
     * S3 servlet reads the raw request URI itself, so those checks are relaxed. Header values must reach the
     * signature check exactly as the client sent them.
     */
    @Bean
    WebServerFactoryCustomizer<JettyServletWebServerFactory> s3Jetty() {
        return factory -> factory.addServerCustomizers(server -> {
            for (Connector connector : server.getConnectors()) {
                HttpConnectionFactory http = connector.getConnectionFactory(HttpConnectionFactory.class);
                if (http != null) {
                    http.getHttpConfiguration().setUriCompliance(UriCompliance.UNSAFE);
                    // Jetty's header cache matches common values case-insensitively and hands back its own
                    // spelling ("charset=UTF-8" for "charset=utf-8"). Signatures cover exact header bytes.
                    http.getHttpConfiguration().setHeaderCacheCaseSensitive(true);
                }
            }
            for (ServletContextHandler context : server.getDescendants(ServletContextHandler.class)) {
                context.getServletHandler().setDecodeAmbiguousURIs(true);
            }
        });
    }
}
