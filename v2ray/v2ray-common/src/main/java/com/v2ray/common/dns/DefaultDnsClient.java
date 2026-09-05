package com.v2ray.common.dns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default DNS client with in-memory TTL caching.
 */
public class DefaultDnsClient implements DnsClient {
    private static final Logger logger = LoggerFactory.getLogger(DefaultDnsClient.class);
    private static final long DEFAULT_TTL_MS = 300_000; // 5 minutes

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    private static class CacheEntry {
        final List<InetAddress> addresses;
        final long expiresAt;

        CacheEntry(List<InetAddress> addresses, long ttlMs) {
            this.addresses = addresses;
            this.expiresAt = System.currentTimeMillis() + ttlMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    @Override
    public List<InetAddress> lookup(String domain) throws Exception {
        if (domain == null || domain.isEmpty()) {
            return Collections.emptyList();
        }

        String key = domain.toLowerCase();
        CacheEntry entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            return entry.addresses;
        }

        logger.debug("Resolving DNS for domain: {}", domain);
        InetAddress[] addrs = InetAddress.getAllByName(domain);
        List<InetAddress> result = Arrays.asList(addrs);

        cache.put(key, new CacheEntry(result, DEFAULT_TTL_MS));
        return result;
    }

    @Override
    public InetAddress lookupFirst(String domain) throws Exception {
        List<InetAddress> list = lookup(domain);
        if (list.isEmpty()) {
            throw new NoSuchElementException("No IP found for domain: " + domain);
        }
        return list.get(0);
    }

    @Override
    public void start() {
        running = true;
        logger.info("DefaultDnsClient started");
    }

    @Override
    public void close() {
        running = false;
        cache.clear();
        logger.info("DefaultDnsClient stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
