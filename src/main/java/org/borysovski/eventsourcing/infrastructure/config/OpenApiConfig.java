package org.borysovski.eventsourcing.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bankEventSourcingOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Event-Sourcing Bank Account API")
                        .version("1.0.0")
                        .description("""
                                Study project demonstrating Event Sourcing with:
                                - PostgreSQL append-only event store
                                - Kafka-based transfer saga (transactional outbox pattern)
                                - MongoDB CQRS read model
                                """));
    }
}
