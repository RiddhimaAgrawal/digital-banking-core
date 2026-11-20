package com.bankcore.config;

import com.bankcore.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Declares the topics; Spring's KafkaAdmin creates them on startup if they do not exist. */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic transactionsCompletedTopic() {
        return TopicBuilder.name(Topics.TRANSACTIONS_COMPLETED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic kycStatusChangedTopic() {
        return TopicBuilder.name(Topics.KYC_STATUS_CHANGED).partitions(1).replicas(1).build();
    }
}
