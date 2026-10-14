package io.github.simpan.config;

import io.github.simpan.api.S3Servlet;
import io.github.simpan.auth.Credentials;
import io.github.simpan.auth.SigV4Verifier;
import io.github.simpan.metrics.S3Metrics;
import io.github.simpan.storage.Storage;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
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
     * S3 object keys can contain characters Tomcat rejects by default in URLs (encoded slashes, brackets, pipes),
     * and uploads can be large, so relax those limits on the S3 connector.
     */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> s3Tomcat() {
        return factory -> factory.addConnectorCustomizers((Connector c) -> {
            c.setEncodedSolidusHandling("passthrough");
            c.setAllowBackslash(true);
            c.setProperty("relaxedPathChars", "[]|{}^\\`\"<>");
            c.setProperty("relaxedQueryChars", "[]|{}^\\`\"<>");
            c.setMaxPostSize(-1);
            c.setProperty("maxSwallowSize", "-1");
        });
    }
}
