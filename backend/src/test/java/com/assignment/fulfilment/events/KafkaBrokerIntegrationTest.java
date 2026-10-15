package com.assignment.fulfilment.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Real Apache Kafka integration test.
 *
 * <p>In accordance with project constraints:
 * - Docker and containers are prohibited.
 * - If a local or managed Kafka broker is available on localhost:9092, this test runs live.
 * - If Kafka is unavailable, this test is marked as BLOCKED rather than using mocks.
 */
class KafkaBrokerIntegrationTest {

    static boolean isKafkaAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 9092), 1000);
            return true;
        } catch (Exception e) {
            System.err.println("BLOCKED: Real Kafka broker is unavailable on localhost:9092. Skipping live Kafka broker integration test as required (no Docker).");
            return false;
        }
    }

    @Test
    @EnabledIf("isKafkaAvailable")
    @DisplayName("Real Kafka Broker: Produce and consume message with partition keying")
    void testRealKafkaProduceAndConsume() {
        String topic = "order-events";
        String aggregateId = UUID.randomUUID().toString();
        String payload = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"aggregateId\":\"" + aggregateId + "\"}";

        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
            producer.send(new ProducerRecord<>(topic, aggregateId, payload));
            producer.flush();
        }

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-real-kafka-group-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(Collections.singletonList(topic));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
            boolean found = false;
            for (ConsumerRecord<String, String> record : records) {
                if (record.key().equals(aggregateId)) {
                    assertThat(record.value()).isEqualTo(payload);
                    found = true;
                    break;
                }
            }
            assertThat(found).isTrue();
        }
    }
}
