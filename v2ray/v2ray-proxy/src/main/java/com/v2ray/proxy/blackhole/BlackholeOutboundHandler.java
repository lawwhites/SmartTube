package com.v2ray.proxy.blackhole;

import com.v2ray.common.net.Destination;
import com.v2ray.common.relay.RelayHandler;
import com.v2ray.proxy.OutboundHandler;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Blackhole outbound handler silently drops or closes connections.
 * Equivalent to proxy/blackhole in Go.
 */
public class BlackholeOutboundHandler implements OutboundHandler {
    private static final Logger logger = LoggerFactory.getLogger(BlackholeOutboundHandler.class);

    private final String tag;
    private volatile boolean running = false;

    public BlackholeOutboundHandler() {
        this("blocked");
    }

    public BlackholeOutboundHandler(String tag) {
        this.tag = tag;
    }

    @Override
    public String getTag() {
        return tag;
    }

    @Override
    public void dispatch(Destination destination, Channel inboundChannel) {
        logger.info("[{}] Blocking connection to: {}", tag, destination);
        RelayHandler.closeOnFlush(inboundChannel);
    }

    @Override
    public void start() {
        running = true;
        logger.info("Blackhole outbound [{}] started", tag);
    }

    @Override
    public void close() {
        running = false;
        logger.info("Blackhole outbound [{}] closed", tag);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
