package com.v2ray.core.inbound;

import com.v2ray.common.dispatcher.Dispatcher;
import com.v2ray.common.lifecycle.Feature;
import com.v2ray.proxy.InboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all inbound proxy handlers. Equivalent to features/inbound.Manager in Go.
 */
public class InboundManager implements Feature {
    private static final Logger logger = LoggerFactory.getLogger(InboundManager.class);

    private final Map<String, InboundHandler> handlers = new ConcurrentHashMap<>();
    private Dispatcher dispatcher;
    private io.netty.channel.EventLoopGroup bossGroup;
    private io.netty.channel.EventLoopGroup workerGroup;
    private volatile boolean running = false;

    public void setDispatcher(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
        for (InboundHandler handler : handlers.values()) {
            handler.setDispatcher(dispatcher);
        }
    }

    public void setEventLoopGroups(io.netty.channel.EventLoopGroup bossGroup, io.netty.channel.EventLoopGroup workerGroup) {
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        for (InboundHandler handler : handlers.values()) {
            handler.setEventLoopGroups(bossGroup, workerGroup);
        }
    }

    public void addHandler(InboundHandler handler) {
        if (dispatcher != null) {
            handler.setDispatcher(dispatcher);
        }
        if (bossGroup != null && workerGroup != null) {
            handler.setEventLoopGroups(bossGroup, workerGroup);
        }
        handlers.put(handler.getTag(), handler);
        logger.info("Registered InboundHandler with tag [{}]", handler.getTag());
    }

    public InboundHandler getHandler(String tag) {
        return handlers.get(tag);
    }

    public Collection<InboundHandler> getAllHandlers() {
        return handlers.values();
    }

    @Override
    public void start() throws Exception {
        for (InboundHandler handler : handlers.values()) {
            handler.start();
        }
        running = true;
        logger.info("InboundManager started with {} handlers", handlers.size());
    }

    @Override
    public void close() {
        running = false;
        for (InboundHandler handler : handlers.values()) {
            try {
                handler.close();
            } catch (Exception e) {
                logger.warn("Error closing inbound handler {}: {}", handler.getTag(), e.getMessage());
            }
        }
        logger.info("InboundManager closed");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public Class<? extends Feature> getFeatureType() {
        return InboundManager.class;
    }
}
