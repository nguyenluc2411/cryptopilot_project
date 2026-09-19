package com.cryptopilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the CryptoPilot modular monolith. Each direct sub-package of
 * {@code com.cryptopilot} is an application module organised by layer
 * (controller, service, repository, entity, dto, calculator, client, job, event).
 *
 * <p>Reference: Richardson, C. (2018). <i>Microservices Patterns</i>. Manning, ch. 1 (the modular monolith).
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 4 (Layered Architecture).
 */
@SpringBootApplication
public class CryptoPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(CryptoPilotApplication.class, args);
    }
}
