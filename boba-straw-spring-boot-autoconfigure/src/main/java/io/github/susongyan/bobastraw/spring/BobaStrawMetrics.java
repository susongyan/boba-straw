package io.github.susongyan.bobastraw.spring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Context-owned gauges. Stable configured names avoid topology-address tag cardinality. */
public final class BobaStrawMetrics implements MeterBinder, AutoCloseable {
    private final List<BobaStrawObservation> observations;
    private final Map<MeterRegistry, List<Meter>> owned = new IdentityHashMap<MeterRegistry, List<Meter>>();
    private boolean closed;

    BobaStrawMetrics(List<BobaStrawObservation> observations) {
        this.observations = observations;
    }

    @Override
    public synchronized void bindTo(MeterRegistry registry) {
        if (closed || owned.containsKey(registry)) {
            return;
        }
        List<Meter> meters = new ArrayList<Meter>();
        owned.put(registry, meters);
        try {
            for (BobaStrawObservation observation : observations) {
                gauge(registry, meters, "boba.straw.ready", observation, value -> value.ready() ? 1 : 0);
                if (observation.hasQueueMetrics()) {
                    gauge(registry, meters, "boba.straw.inflight", observation, BobaStrawObservation::inFlight);
                    gauge(registry, meters, "boba.straw.queued.bytes", observation, BobaStrawObservation::queuedBytes);
                }
                if (observation.hasTopologyMetrics()) {
                    gauge(registry, meters, "boba.straw.topology.successes", observation,
                        BobaStrawObservation::topologySuccesses);
                    gauge(registry, meters, "boba.straw.topology.failures", observation,
                        BobaStrawObservation::topologyFailures);
                }
            }
        } catch (RuntimeException failure) {
            for (Meter meter : meters) {
                registry.remove(meter);
            }
            owned.remove(registry);
            throw failure;
        }
    }

    private void gauge(MeterRegistry registry, List<Meter> meters, String name,
        BobaStrawObservation observation, ToDoubleFunction<BobaStrawObservation> value) {
        if (registry.find(name).tag("client", observation.name).meter() != null) {
            throw new IllegalStateException("Boba Straw metric identity is already registered");
        }
        List<Meter> existing = registry.getMeters();
        Meter meter = Gauge.builder(name, observation, value).tag("client", observation.name).register(registry);
        if (existing.contains(meter)) {
            throw new IllegalStateException("MeterFilter mapped Boba Straw onto an existing metric");
        }
        meters.add(meter);
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Map.Entry<MeterRegistry, List<Meter>> entry : owned.entrySet()) {
            for (Meter meter : entry.getValue()) {
                entry.getKey().remove(meter);
            }
        }
        owned.clear();
    }
}
