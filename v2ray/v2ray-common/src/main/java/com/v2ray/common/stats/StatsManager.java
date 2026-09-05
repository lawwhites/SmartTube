package com.v2ray.common.stats;

import com.v2ray.common.lifecycle.Feature;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Statistics manager tracking traffic across inbounds, outbounds, and users.
 * Equivalent to features/stats.Manager in Go.
 */
public class StatsManager implements Feature {
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public Counter registerCounter(String name) {
        return counters.computeIfAbsent(name, Counter::new);
    }

    public Counter getCounter(String name) {
        return counters.get(name);
    }

    public Map<String, Counter> getAllCounters() {
        return counters;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void close() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public Class<? extends Feature> getFeatureType() {
        return StatsManager.class;
    }
}
