package org.box1.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.box1.asset.domain.RejectReason;
import org.box1.asset.domain.RejectedReadingException;
import org.box1.asset.ingest.ReadingPublisher;
import org.box1.asset.ingest.TestAccess;
import org.junit.jupiter.api.Test;

/** TEL-BP-001: bounded in-flight readings, pause, and counted delivery failure (D-010). Queue capacity is 3 here. */
class ReadingPublisherTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MockProducer<String, byte[]> producer = new MockProducer<>(false, null, new StringSerializer(), new ByteArraySerializer());
    private final ReadingPublisher publisher = new ReadingPublisher(producer, TestCatalog.config(false, List.of(), null),
            Clock.systemUTC(), registry);

    @Test
    void brokerOutageFillsTheBoundThenRefusesWithBackpressure() {
        CompletableFuture<?> a = publisher.publish("a", "asset_1", new byte[1]);
        publisher.publish("b", "asset_1", new byte[1]);
        publisher.publish("c", "asset_1", new byte[1]);
        TestAccess.drainOnce(publisher);
        assertEquals(3, producer.history().size(), "handed to Kafka but not acknowledged");
        assertEquals(3, publisher.inFlight());
        RejectedReadingException refused = assertThrows(RejectedReadingException.class, () -> publisher.publish("d", "asset_1", new byte[1]));
        assertEquals(RejectReason.BACKPRESSURE, refused.reason());
        producer.completeNext();
        assertTrue(a.isDone() && !a.isCompletedExceptionally());
        assertEquals(2, publisher.inFlight(), "an acknowledgement frees one slot");
        publisher.publish("e", "asset_1", new byte[1]);
    }

    @Test
    void deliveryFailureIsCountedAndReportedNotSilent() {
        CompletableFuture<?> a = publisher.publish("a", "asset_1", new byte[1]);
        TestAccess.drainOnce(publisher);
        producer.errorNext(new org.apache.kafka.common.errors.TimeoutException("expired"));
        assertTrue(a.isCompletedExceptionally());
        assertEquals(1.0, registry.counter("asset.ingest.dropped", "reason", "delivery_failed").count());
        assertEquals(0, publisher.inFlight());
    }

    @Test
    void pausedProducerHoldsReadingsWithinTheBoundAndResumes() {
        publisher.pause(Duration.ofSeconds(30));
        publisher.publish("a", "asset_1", new byte[1]);
        publisher.publish("b", "asset_1", new byte[1]);
        publisher.publish("c", "asset_1", new byte[1]);
        TestAccess.drainOnce(publisher);
        assertEquals(0, producer.history().size(), "nothing is sent while paused");
        assertThrows(RejectedReadingException.class, () -> publisher.publish("d", "asset_1", new byte[1]));
        assertTrue(publisher.isPaused());
        publisher.resume();
        assertFalse(publisher.isPaused());
        TestAccess.drainOnce(publisher);
        assertEquals(3, producer.history().size());
    }
}
