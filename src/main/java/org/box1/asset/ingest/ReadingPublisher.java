package org.box1.asset.ingest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.box1.asset.config.AssetConfig;
import org.box1.asset.domain.RejectReason;
import org.box1.asset.domain.RejectedReadingException;
import org.jboss.logging.Logger;

/**
 * Bounded producer path (D-004, D-010). At most {@code queueCapacity} readings are in flight, counting both those
 * waiting for the sender and those handed to Kafka but not yet acknowledged. When the bound is reached, new readings
 * are refused with BACKPRESSURE instead of growing memory. A paused producer keeps its queue and resumes on its own.
 */
@ApplicationScoped
public class ReadingPublisher {
    private static final Logger LOG = Logger.getLogger(ReadingPublisher.class);

    private final Producer<String, byte[]> producer;
    private final String topic;
    private final int capacity;
    private final Semaphore permits;
    private final LinkedBlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    private final AtomicReference<Instant> pausedUntil = new AtomicReference<>(Instant.EPOCH);
    private final Clock clock;
    private final Counter dropped;
    private final Counter published;
    private volatile boolean running;
    private Thread sender;

    public ReadingPublisher(Producer<String, byte[]> producer, AssetConfig config, Clock clock, MeterRegistry registry) {
        this.producer = producer;
        this.topic = config.ingest().topic();
        this.capacity = config.ingest().queueCapacity();
        this.permits = new Semaphore(capacity);
        this.clock = clock;
        this.dropped = registry.counter("asset.ingest.dropped", "reason", "delivery_failed");
        this.published = registry.counter("asset.ingest.published");
        registry.gauge("asset.producer.in.flight", this, ReadingPublisher::inFlight);
        registry.gauge("asset.producer.queue.capacity", this, p -> p.capacity);
        registry.gauge("asset.producer.paused", this, p -> p.isPaused() ? 1 : 0);
    }

    void start(@Observes StartupEvent event) {
        running = true;
        sender = Thread.ofPlatform().name("asset-reading-sender").daemon().start(this::drain);
    }

    void stop(@Observes ShutdownEvent event) {
        running = false;
        if (sender != null) {
            sender.interrupt();
        }
        int unsent = queue.size();
        if (unsent > 0) {
            LOG.warnf("Shutdown with %d readings not yet handed to Kafka; their clients were not sent 202", unsent);
        }
    }

    /** Queues a record; the future completes when the broker acknowledges or delivery fails. */
    public CompletableFuture<RecordMetadata> publish(String eventId, String key, byte[] value) {
        if (!permits.tryAcquire()) {
            throw new RejectedReadingException(RejectReason.BACKPRESSURE,
                    "Producer queue is full (" + capacity + " readings in flight); retry later");
        }
        Pending pending = new Pending(eventId, new ProducerRecord<>(topic, key, value), new CompletableFuture<>());
        queue.add(pending);
        return pending.result();
    }

    public int inFlight() {
        return capacity - permits.availablePermits();
    }

    public int capacity() {
        return capacity;
    }

    public boolean isPaused() {
        return clock.instant().isBefore(pausedUntil.get());
    }

    public Instant pausedUntil() {
        return pausedUntil.get();
    }

    /** Pauses handing records to Kafka until {@code duration} from now. Used only by the chaos catalog. */
    public void pause(Duration duration) {
        pausedUntil.set(clock.instant().plus(duration));
    }

    public void resume() {
        pausedUntil.set(Instant.EPOCH);
    }

    private void drain() {
        while (running) {
            try {
                if (isPaused()) {
                    Thread.sleep(50);
                    continue;
                }
                Pending pending = queue.poll(200, TimeUnit.MILLISECONDS);
                if (pending != null) {
                    send(pending);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException error) {
                LOG.error("Reading sender loop error", error);
            }
        }
    }

    /** Package-private so tests can drive the sender synchronously. */
    void send(Pending pending) {
        try {
            producer.send(pending.record(), (metadata, error) -> complete(pending, metadata, error));
        } catch (RuntimeException error) {
            complete(pending, null, error);
        }
    }

    void drainOnce() {
        Pending pending;
        while (!isPaused() && (pending = queue.poll()) != null) {
            send(pending);
        }
    }

    private void complete(Pending pending, RecordMetadata metadata, Exception error) {
        permits.release();
        if (error == null) {
            published.increment();
            pending.result().complete(metadata);
        } else {
            dropped.increment();
            LOG.warnf("Reading %s not delivered (%s); counted as dropped", pending.eventId(), error.getClass().getSimpleName());
            pending.result().completeExceptionally(error);
        }
    }

    record Pending(String eventId, ProducerRecord<String, byte[]> record, CompletableFuture<RecordMetadata> result) {
    }
}
