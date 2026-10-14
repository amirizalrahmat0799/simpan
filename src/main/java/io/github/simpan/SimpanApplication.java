package io.github.simpan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** simpan: an S3-compatible object storage server. */
@SpringBootApplication
public class SimpanApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimpanApplication.class, args);
    }
}
