package com.v2ray.common.stats;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe counter for traffic metrics.
 * Equivalent to features/stats.Counter in Go.
 */
public class Counter {
    private final String name;
    private final AtomicLong value = new AtomicLong(0);

    public Counter(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public long value() {
        return value.get();
    }

    public long add(long delta) {
        return value.addAndGet(delta);
    }

    public void set(long val) {
        value.set(val);
    }

    @Override
    public String toString() {
        return name + "=" + value.get();
    }
}
