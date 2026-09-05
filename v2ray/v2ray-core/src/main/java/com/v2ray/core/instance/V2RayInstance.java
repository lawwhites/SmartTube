package com.v2ray.core.instance;

import com.v2ray.common.dispatcher.Dispatcher;
import com.v2ray.common.lifecycle.Feature;
import com.v2ray.common.lifecycle.Lifecycle;
import com.v2ray.core.dispatcher.DefaultDispatcher;
import com.v2ray.core.inbound.InboundManager;
import com.v2ray.core.outbound.OutboundManager;
import com.v2ray.router.DefaultRouter;
import com.v2ray.router.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V2RayInstance is the central container for all V2Ray features and proxy pipelines.
 * Equivalent to core.Instance in Go.
 */
public class V2RayInstance implements Lifecycle {
    private static final Logger logger = LoggerFactory.getLogger(V2RayInstance.class);

    private final Map<Class<? extends Feature>, Feature> features = new ConcurrentHashMap<>();
    private final io.netty.channel.EventLoopGroup bossGroup;
    private final io.netty.channel.EventLoopGroup workerGroup;
    private InboundManager inboundManager;
    private OutboundManager outboundManager;
    private Router router;
    private Dispatcher dispatcher;
    private volatile boolean running = false;

    public V2RayInstance() {
        // Initialize mobile-friendly, platform-detected shared EventLoopGroups
        this.bossGroup = com.v2ray.common.net.TransportHelper.createBossGroup();
        this.workerGroup = com.v2ray.common.net.TransportHelper.createWorkerGroup();

        // Initialize default core features
        this.inboundManager = new InboundManager();
        this.inboundManager.setEventLoopGroups(bossGroup, workerGroup);
        this.outboundManager = new OutboundManager();
        this.router = new DefaultRouter("direct");
        this.dispatcher = new DefaultDispatcher(router, outboundManager);

        inboundManager.setDispatcher(dispatcher);

        addFeature(inboundManager);
        addFeature(outboundManager);
        addFeature(router);
        addFeature(dispatcher);
    }

    public void addFeature(Feature feature) {
        features.put(feature.getFeatureType(), feature);
        if (feature instanceof InboundManager) {
            this.inboundManager = (InboundManager) feature;
        } else if (feature instanceof OutboundManager) {
            this.outboundManager = (OutboundManager) feature;
        } else if (feature instanceof Router) {
            this.router = (Router) feature;
            this.dispatcher = new DefaultDispatcher(this.router, this.outboundManager);
            this.inboundManager.setDispatcher(this.dispatcher);
        } else if (feature instanceof Dispatcher) {
            this.dispatcher = (Dispatcher) feature;
            this.inboundManager.setDispatcher(this.dispatcher);
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends Feature> T getFeature(Class<T> type) {
        return (T) features.get(type);
    }

    public InboundManager getInboundManager() {
        return inboundManager;
    }

    public OutboundManager getOutboundManager() {
        return outboundManager;
    }

    public Router getRouter() {
        return router;
    }

    public Dispatcher getDispatcher() {
        return dispatcher;
    }

    public io.netty.channel.EventLoopGroup getBossGroup() {
        return bossGroup;
    }

    public io.netty.channel.EventLoopGroup getWorkerGroup() {
        return workerGroup;
    }

    @Override
    public synchronized void start() throws Exception {
        if (running) {
            logger.warn("V2RayInstance is already running");
            return;
        }

        logger.info("Starting V2RayInstance...");
        for (Feature feature : features.values()) {
            feature.start();
        }
        running = true;
        logger.info("V2RayInstance started successfully");
    }

    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }

        logger.info("Stopping V2RayInstance...");
        for (Feature feature : features.values()) {
            try {
                feature.close();
            } catch (Exception e) {
                logger.warn("Error closing feature {}: {}", feature.getClass().getSimpleName(), e.getMessage());
            }
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        running = false;
        logger.info("V2RayInstance stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
