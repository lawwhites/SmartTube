package com.v2ray.proxy.freedom;

import com.v2ray.common.net.Destination;
import com.v2ray.common.relay.RelayHandler;
import com.v2ray.proxy.OutboundHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Freedom outbound handler directly connects to the destination over the internet.
 * Equivalent to proxy/freedom in Go.
 */
public class FreedomOutboundHandler implements OutboundHandler {
    private static final Logger logger = LoggerFactory.getLogger(FreedomOutboundHandler.class);

    private final String tag;
    private volatile boolean running = false;

    public FreedomOutboundHandler() {
        this("direct");
    }

    public FreedomOutboundHandler(String tag) {
        this.tag = tag;
    }

    @Override
    public String getTag() {
        return tag;
    }

    @Override
    public void dispatch(Destination destination, Channel inboundChannel) {
        logger.info("[{}] Dialing direct target destination: {}", tag, destination);

        // Attach pending buffer handler immediately so any inbound data is buffered
        com.v2ray.common.relay.PendingBufferHandler pendingHandler = new com.v2ray.common.relay.PendingBufferHandler();
        inboundChannel.pipeline().addLast("pending-buffer", pendingHandler);

        Bootstrap b = new Bootstrap();
        b.group(inboundChannel.eventLoop())
         .channel(com.v2ray.common.net.TransportHelper.socketChannelClass(inboundChannel));
        com.v2ray.common.net.TransportHelper.applyClientOptions(b);
        b.handler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 // Pipeline will be populated after connection is established
             }
         });

        b.connect(destination.getAddress(), destination.getPort()).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                Channel outboundChannel = future.channel();
                logger.debug("[{}] Connected to destination: {}", tag, destination);

                // Flush buffered initial payload
                pendingHandler.flushTo(outboundChannel);
                inboundChannel.pipeline().remove(pendingHandler);

                // Set up bidirectional relaying
                outboundChannel.pipeline().addLast("freedom-relay", new RelayHandler(inboundChannel, "freedom->inbound"));
                inboundChannel.pipeline().addLast("inbound-relay", new RelayHandler(outboundChannel, "inbound->freedom"));

                // Ensure inbound channel is reading
                inboundChannel.config().setAutoRead(true);
                inboundChannel.read();
            } else {
                logger.warn("[{}] Failed to connect to {}: {}", tag, destination, future.cause().getMessage());
                pendingHandler.releaseAll();
                RelayHandler.closeOnFlush(inboundChannel);
            }
        });
    }

    @Override
    public void start() {
        running = true;
        logger.info("Freedom outbound [{}] started", tag);
    }

    @Override
    public void close() {
        running = false;
        logger.info("Freedom outbound [{}] closed", tag);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
