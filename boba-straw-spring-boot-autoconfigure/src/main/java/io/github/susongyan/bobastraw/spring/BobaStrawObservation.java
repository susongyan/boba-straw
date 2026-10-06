package io.github.susongyan.bobastraw.spring;

import io.github.susongyan.bobastraw.BobaStrawClient;
import io.github.susongyan.bobastraw.BobaStrawClientMetrics;
import io.github.susongyan.bobastraw.BobaStrawClusterClient;
import io.github.susongyan.bobastraw.BobaStrawConnectionState;
import io.github.susongyan.bobastraw.BobaStrawSentinelClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Local snapshots only: collecting health or meters never submits a Redis command. */
final class BobaStrawObservation {
    final String name;
    private final AutoCloseable client;

    BobaStrawObservation(String name, AutoCloseable client) {
        this.name = name;
        this.client = client;
    }

    boolean ready() {
        if (client instanceof BobaStrawSentinelClient) {
            return ((BobaStrawSentinelClient) client).connectionState() == BobaStrawConnectionState.READY;
        }
        List<BobaStrawClientMetrics> metrics = metrics();
        if (metrics.isEmpty()) {
            return false;
        }
        for (BobaStrawClientMetrics metric : metrics) {
            if (metric.sharedConnectionState() != BobaStrawConnectionState.READY) {
                return false;
            }
        }
        return true;
    }

    List<BobaStrawClientMetrics> metrics() {
        if (client instanceof BobaStrawClient) {
            return Collections.singletonList(((BobaStrawClient) client).metrics());
        }
        if (client instanceof BobaStrawClusterClient) {
            return new ArrayList<BobaStrawClientMetrics>(
                ((BobaStrawClusterClient) client).nodeMetrics().values());
        }
        // Sentinel currently exposes lifecycle/discovery only; do not invent data-node counters.
        return Collections.emptyList();
    }

    double inFlight() {
        long value = 0;
        for (BobaStrawClientMetrics metric : metrics()) {
            value += metric.inFlightCommands();
        }
        return value;
    }

    double queuedBytes() {
        long value = 0;
        for (BobaStrawClientMetrics metric : metrics()) {
            value += metric.queuedWriteBytes();
        }
        return value;
    }

    boolean hasQueueMetrics() {
        return !(client instanceof BobaStrawSentinelClient);
    }

    boolean hasTopologyMetrics() {
        return client instanceof BobaStrawClusterClient || client instanceof BobaStrawSentinelClient;
    }

    double topologySuccesses() {
        return client instanceof BobaStrawClusterClient
            ? ((BobaStrawClusterClient) client).topologyRefreshSuccesses()
            : ((BobaStrawSentinelClient) client).successfulDiscoveries();
    }

    double topologyFailures() {
        return client instanceof BobaStrawClusterClient
            ? ((BobaStrawClusterClient) client).topologyRefreshFailures()
            : ((BobaStrawSentinelClient) client).failedDiscoveries();
    }

    static List<BobaStrawObservation> all(Map<String, BobaStrawClient> standalone,
        Map<String, BobaStrawClusterClient> cluster, Map<String, BobaStrawSentinelClient> sentinel,
        BobaStrawClients named) {
        List<BobaStrawObservation> result = new ArrayList<BobaStrawObservation>();
        append(result, "bean:", standalone);
        append(result, "bean:", cluster);
        append(result, "bean:", sentinel);
        append(result, "named:", named.entries());
        return result;
    }

    private static void append(List<BobaStrawObservation> result, String prefix,
        Map<String, ? extends AutoCloseable> clients) {
        for (Map.Entry<String, ? extends AutoCloseable> entry : clients.entrySet()) {
            result.add(new BobaStrawObservation(prefix + entry.getKey(), entry.getValue()));
        }
    }
}
