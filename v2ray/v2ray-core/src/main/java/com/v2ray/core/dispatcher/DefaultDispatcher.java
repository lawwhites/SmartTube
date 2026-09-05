package com.v2ray.core.dispatcher;

import com.v2ray.common.dispatcher.Dispatcher;
import com.v2ray.common.lifecycle.Feature;
import com.v2ray.common.net.Destination;
import com.v2ray.core.outbound.OutboundManager;
import com.v2ray.proxy.OutboundHandler;
import com.v2ray.router.Router;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DefaultDispatcher implements the core Dispatcher feature,
 * connecting Inbound traffic through the Router to Outbound handlers.
 * Equivalent to app/dispatcher.DefaultDispatcher in Go.
 */
public class DefaultDispatcher implements Dispatcher {
    private static final Logger logger = LoggerFactory.getLogger(DefaultDispatcher.class);

    private final Router router;
    private final OutboundManager outboundManager;
    private volatile boolean running = false;

    public DefaultDispatcher(Router router, OutboundManager outboundManager) {
        this.router = router;
        this.outboundManager = outboundManager;
    }

    @Override
    public void dispatch(Destination destination, Channel inboundChannel, String inboundTag) {
        // Consult the router to decide outbound tag
        String outboundTag = null;
        if (router != null) {
            outboundTag = router.route(destination, inboundTag);
        }

        OutboundHandler outboundHandler = outboundManager.getHandler(outboundTag);
        if (outboundHandler == null) {
            logger.error("No outbound handler found for tag [{}] or default. Closing connection.", outboundTag);
            inboundChannel.close();
            return;
        }

        logger.debug("Dispatching to outbound [{}] for destination: {}", outboundHandler.getTag(), destination);
        outboundHandler.dispatch(destination, inboundChannel);
    }

    @Override
    public void start() {
        running = true;
        logger.info("DefaultDispatcher started");
    }

    @Override
    public void close() {
        running = false;
        logger.info("DefaultDispatcher closed");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public Class<? extends Feature> getFeatureType() {
        return Dispatcher.class;
    }
}
