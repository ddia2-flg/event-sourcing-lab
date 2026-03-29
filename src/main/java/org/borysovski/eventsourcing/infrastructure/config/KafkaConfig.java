package org.borysovski.eventsourcing.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic transferRequestedTopic() {
        return TopicBuilder.name("transfer.requested")
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic transferResultTopic() {
        return TopicBuilder.name("transfer.result")
                .partitions(6)
                .replicas(1)
                .build();
    }
}
