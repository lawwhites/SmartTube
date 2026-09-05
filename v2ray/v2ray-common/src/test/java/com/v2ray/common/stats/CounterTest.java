package com.v2ray.common.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CounterTest {

    @Test
    public void testCounterOperations() {
        Counter counter = new Counter("test-counter");
        assertEquals(0, counter.value());

        counter.add(1024);
        assertEquals(1024, counter.value());

        counter.add(2048);
        assertEquals(3072, counter.value());

        counter.set(0);
        assertEquals(0, counter.value());
    }

    @Test
    public void testStatsManager() {
        StatsManager manager = new StatsManager();
        Counter c1 = manager.registerCounter("inbound>>>socks>>>traffic>>>downlink");
        c1.add(500);

        Counter c2 = manager.getCounter("inbound>>>socks>>>traffic>>>downlink");
        assertEquals(500, c2.value());
    }
}
