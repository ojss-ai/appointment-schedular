// TASK: ATOM-KAFKA-007
package com.scheduler.notification.repository;

import com.scheduler.notification.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Dedup lookups always hit the (consumer_group, message_key) index. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    boolean existsByConsumerGroupAndMessageKey(String consumerGroup, String messageKey);

    long countByConsumerGroupAndMessageKey(String consumerGroup, String messageKey);

    long countByConsumerGroup(String consumerGroup);
}
