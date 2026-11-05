package org.box1.asset.ops;

import java.time.Clock;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/** Wall clock, replaceable in tests. */
public class ClockProducer {
    @Produces
    @ApplicationScoped
    @DefaultBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
