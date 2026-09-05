package com.v2ray.proxy.dokodemo;

import com.v2ray.common.dispatcher.Dispatcher;
import com.v2ray.common.net.Destination;
import com.v2ray.common.net.Network;
import com.v2ray.proxy.InboundHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Dokodemo-door inbound handler (any-door / transparent port forwarder).
 * Forwards any inbound TCP connection directly to a designated target destination.
 * Equivalent to proxy/dokodemo in Go.
 */
public class DokodemoInboundHandler implements InboundHandler {
    private static final Logger logger = LoggerFactory.getLogger(DokodemoInboundHandler.class);

    private final String tag;
    private final String listen;
    private final int port;
    private final Destination targetDestination;
    private Dispatcher dispatcher;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private boolean ownEventLoopGroups = true;
    private Channel serverChannel;
    private volatile boolean running = false;

    public DokodemoInboundHandler(String tag, String listen, int port, String targetAddress, int targetPort) {
        this(tag, listen, port, Destination.tcp(targetAddress, targetPort));
    }

    public DokodemoInboundHandler(String tag, String listen, int port, Destination targetDestination) {
        this.tag = tag;
        this.listen = (listen == null || listen.isEmpty()) ? "0.0.0.0" : listen;
        this.port = port;
        this.targetDestination = targetDestination;
    }

    @Override
    public String getTag() {
        return tag;
    }

    @Override
    public int getPort() {
        if (serverChannel != null && serverChannel.localAddress() instanceof InetSocketAddress) {
            return ((InetSocketAddress) serverChannel.localAddress()).getPort();
        }
        return port;
    }

    @Override
    public String getListen() {
        return listen;
    }

    @Override
    public void setDispatcher(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void setEventLoopGroups(EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.ownEventLoopGroups = false;
    }

    @Override
    public void start() throws Exception {
        if (bossGroup == null) {
            bossGroup = com.v2ray.common.net.TransportHelper.createBossGroup();
            workerGroup = com.v2ray.common.net.TransportHelper.createWorkerGroup();
            ownEventLoopGroups = true;
        }

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
         .channel(com.v2ray.common.net.TransportHelper.serverSocketChannelClass());
        com.v2ray.common.net.TransportHelper.applyServerOptions(b);
        b.childHandler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 logger.debug("[{}] Incoming dokodemo connection from {}, dispatching to {}",
                         tag, ch.remoteAddress(), targetDestination);
                 ch.config().setAutoRead(false);
                 if (dispatcher != null) {
                     dispatcher.dispatch(targetDestination, ch, tag);
                 } else {
                     ch.close();
                 }
             }
         });

        serverChannel = b.bind(listen, port).sync().channel();
        running = true;
        logger.info("Dokodemo inbound [{}] listening on {}:{} -> forwarding to {}",
                tag, listen, getPort(), targetDestination);
    }

    @Override
    public void close() {
        running = false;
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (ownEventLoopGroups) {
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
        }
        logger.info("Dokodemo inbound [{}] stopped", tag);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
