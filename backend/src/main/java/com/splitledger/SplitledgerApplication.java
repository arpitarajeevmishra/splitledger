package com.splitledger;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Without this, every @Scheduled method is silently never called -- the purge
// job would look present and do nothing.
@EnableScheduling
@SpringBootApplication
public class SplitledgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SplitledgerApplication.class, args);
    }
}
