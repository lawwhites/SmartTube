package com.v2ray.core.outbound;

import com.v2ray.common.lifecycle.Feature;
import com.v2ray.proxy.OutboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all outbound proxy handlers. Equivalent to features/outbound.Manager in Go.
 */
public class OutboundManager implements Feature {
    private static final Logger logger = LoggerFactory.getLogger(OutboundManager.class);

    private final Map<String, OutboundHandler> handlers = new ConcurrentHashMap<>();
    private OutboundHandler defaultHandler;
    private volatile boolean running = false;

    public void addHandler(OutboundHandler handler) {
        handlers.put(handler.getTag(), handler);
        if (defaultHandler == null) {
            defaultHandler = handler;
        }
        logger.info("Registered OutboundHandler with tag [{}]", handler.getTag());
    }

    public OutboundHandler getHandler(String tag) {
        if (tag == null) {
            return defaultHandler;
        }
        return handlers.getOrDefault(tag, defaultHandler);
    }

    public OutboundHandler getDefaultHandler() {
        return defaultHandler;
    }

    public void setDefaultHandler(OutboundHandler defaultHandler) {
        this.defaultHandler = defaultHandler;
    }

    public Collection<OutboundHandler> getAllHandlers() {
        return handlers.values();
    }

    @Override
    public void start() throws Exception {
        for (OutboundHandler handler : handlers.values()) {
            handler.start();
        }
        running = true;
        logger.info("OutboundManager started with {} handlers", handlers.size());
    }

    @Override
    public void close() {
        running = false;
        for (OutboundHandler handler : handlers.values()) {
            try {
                handler.close();
            } catch (Exception e) {
                logger.warn("Error closing outbound handler {}: {}", handler.getTag(), e.getMessage());
            }
        }
        logger.info("OutboundManager closed");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public Class<? extends Feature> getFeatureType() {
        return OutboundManager.class;
    }
}
