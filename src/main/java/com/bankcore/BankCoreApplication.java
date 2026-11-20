package com.bankcore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the Digital Banking Core platform.
 *
 * <p>{@code @EnableScheduling} turns on the background jobs: the outbox publisher
 * (DB -> Kafka) and the monthly statement generator.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class BankCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankCoreApplication.class, args);
    }
}
