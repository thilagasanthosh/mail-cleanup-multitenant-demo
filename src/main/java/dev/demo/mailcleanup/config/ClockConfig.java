package dev.demo.mailcleanup.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {
    /** Injected everywhere time matters, so tests can control it. */
    @Bean
    public Clock clock() { return Clock.systemUTC(); }
}
