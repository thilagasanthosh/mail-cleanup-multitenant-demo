package dev.demo.mailcleanup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Demo: multi-tenant post-delivery email cleanup (pull a malicious email after delivery).
 * A confirmed-malicious email is quarantined from every affected mailbox, across tenants,
 * through one provider interface (Microsoft 365 Graph, Google Gmail API, or an offline fake).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MailCleanupApplication {
    public static void main(String[] args) {
        SpringApplication.run(MailCleanupApplication.class, args);
    }
}
