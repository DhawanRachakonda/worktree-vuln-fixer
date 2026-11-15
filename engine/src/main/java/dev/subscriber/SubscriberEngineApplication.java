package dev.subscriber;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Receives issue-report messages, runs one interactive fix session per message in its own window, and records every
 * session in an H2 file database.
 */
@SpringBootApplication
public class SubscriberEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(SubscriberEngineApplication.class, args);
    }
}
