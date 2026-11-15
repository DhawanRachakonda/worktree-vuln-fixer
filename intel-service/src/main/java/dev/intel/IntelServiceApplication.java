package dev.intel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IntelServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntelServiceApplication.class, args);
    }
}
