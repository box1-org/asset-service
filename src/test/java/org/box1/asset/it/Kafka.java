package org.box1.asset.it;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Plain clients against the local test broker. */
final class Kafka {
    static final String BOOTSTRAP = System.getenv().getOrDefault("TEST_KAFKA", "localhost:19092");

    private Kafka() {
    }

    static void produce(String topic, String key, String value) throws Exception {
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of("bootstrap.servers", BOOTSTRAP),
                new StringSerializer(), new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(topic, key, value.getBytes(StandardCharsets.UTF_8))).get();
        }
    }

    /**
     * Reads the topic from records written at or after {@code since} until {@code done} holds or the timeout passes.
     * Seeking by time keeps the read short on a topic that holds earlier local runs.
     */
    static List<ConsumerRecord<String, byte[]>> read(String topic, java.time.Instant since,
            Predicate<List<ConsumerRecord<String, byte[]>>> done, Duration timeout) {
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of("bootstrap.servers", BOOTSTRAP, "group.id",
                "it-reader-" + UUID.randomUUID(), "auto.offset.reset", "earliest", "enable.auto.commit", "false"),
                new StringDeserializer(), new ByteArrayDeserializer())) {
            List<org.apache.kafka.common.TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new org.apache.kafka.common.TopicPartition(topic, p.partition())).toList();
            consumer.assign(partitions);
            Map<org.apache.kafka.common.TopicPartition, Long> times = new java.util.HashMap<>();
            partitions.forEach(p -> times.put(p, since.toEpochMilli()));
            Map<org.apache.kafka.common.TopicPartition, org.apache.kafka.clients.consumer.OffsetAndTimestamp> offsets = consumer.offsetsForTimes(times);
            Map<org.apache.kafka.common.TopicPartition, Long> ends = consumer.endOffsets(partitions);
            for (org.apache.kafka.common.TopicPartition p : partitions) {
                org.apache.kafka.clients.consumer.OffsetAndTimestamp at = offsets.get(p);
                consumer.seek(p, at == null ? ends.get(p) : at.offset());
            }
            long end = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < end && !done.test(records)) {
                consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            }
        }
        return records;
    }
}
