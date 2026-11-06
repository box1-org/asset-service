package org.box1.asset.ingest;

import java.util.HashMap;
import java.util.Map;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** One producer for readings and the alert outbox (D-010). Tests replace it with a MockProducer. */
public class KafkaProducerFactory {

    @Produces
    @ApplicationScoped
    @DefaultBean
    Producer<String, byte[]> producer(@ConfigProperty(name = "kafka.bootstrap.servers") String bootstrap) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "asset-service-producer");
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 60_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 15_000);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return new KafkaProducer<>(config);
    }

    void close(@jakarta.enterprise.inject.Disposes Producer<String, byte[]> producer) {
        producer.close(java.time.Duration.ofSeconds(5));
    }
}
