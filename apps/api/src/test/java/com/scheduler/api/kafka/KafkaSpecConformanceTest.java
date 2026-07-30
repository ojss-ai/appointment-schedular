// TASK: ATOM-KAFKA-004 / ATOM-KAFKA-005 (producer config + schema validity)
package com.scheduler.api.kafka;

import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Static conformance checks against docs/KAFKA-SPEC.md: producer properties
 * (section 7) and parseability of the shared Avro schemas (section 3 /
 * NFR-2.2). No containers needed — pure configuration verification.
 */
class KafkaSpecConformanceTest {

    private static final String SCHEMA_DIR = "../../infra/kafka/schemas";

    @Test
    void producerConfig_matchesKafkaSpecSection7() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties props = yaml.getObject();
        assertThat(props).isNotNull();

        assertThat(props.getProperty("spring.kafka.producer.acks")).isEqualTo("all");
        assertThat(props.getProperty("spring.kafka.producer.retries")).isEqualTo("3");
        assertThat(props.getProperty("spring.kafka.producer.key-serializer"))
            .isEqualTo("org.apache.kafka.common.serialization.StringSerializer");
        assertThat(props.getProperty("spring.kafka.producer.value-serializer"))
            .isEqualTo("io.confluent.kafka.serializers.KafkaAvroSerializer");
        assertThat(props.getProperty(
            "spring.kafka.producer.properties.enable.idempotence")).isEqualTo("true");
        assertThat(props.getProperty(
            "spring.kafka.producer.properties.max.in.flight.requests.per.connection"))
            .isEqualTo("5");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "booking-lifecycle-event.avsc",
        "notification-command.avsc",
        "audit-event.avsc"
    })
    void avroSchemas_areValidAndGenericallyNamed(String fileName) throws Exception {
        File file = new File(SCHEMA_DIR, fileName);
        assumeTrue(file.exists(), "shared .avsc not found relative to module dir");
        Schema schema = new Schema.Parser().parse(file);

        assertThat(schema.getType()).isEqualTo(Schema.Type.RECORD);
        assertThat(schema.getNamespace()).isEqualTo("io.scheduler.events");
        assertThat(schema.getField("tenantId"))
            .as("every event schema carries tenantId (ADR-004)").isNotNull();
        // Domain abstraction guard: no industry-specific terms
        String raw = schema.toString().toLowerCase();
        assertThat(raw).doesNotContain("doctor", "patient", "vehicle", "mechanic");
    }
}
