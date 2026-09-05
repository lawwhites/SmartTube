package com.v2ray.proxy.vless;

import com.v2ray.common.dispatcher.Dispatcher;
import com.v2ray.common.net.Destination;
import com.v2ray.proxy.InboundHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * VLESS Inbound Handler.
 * Supports VLESS protocol validation, decodes requests, and forwards to Dispatcher.
 * Equivalent to proxy/vless/inbound in Go.
 */
public class VlessInboundHandler implements InboundHandler {
    private static final Logger logger = LoggerFactory.getLogger(VlessInboundHandler.class);

    private final String tag;
    private final String listen;
    private final int port;
    private final Set<UUID> allowedUsers = new HashSet<>();
    private Dispatcher dispatcher;

    public VlessInboundHandler(String tag, String listen, int port) {
        this.tag = tag;
        this.listen = (listen == null || listen.isEmpty()) ? "0.0.0.0" : listen;
        this.port = port;
    }

    public void addAllowedUser(UUID uuid) {
        allowedUsers.add(uuid);
    }

    @Override
    public String getTag() {
        return tag;
    }

    @Override
    public int getPort() {
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

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private boolean ownEventLoopGroups = true;
    private Channel serverChannel;
    private volatile boolean running = false;
    private io.netty.handler.ssl.SslContext sslContext;

    public void setSslContext(io.netty.handler.ssl.SslContext sslContext) {
        this.sslContext = sslContext;
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
                 if (sslContext != null) {
                     ch.pipeline().addLast("tls", sslContext.newHandler(ch.alloc()));
                 }
                 ch.pipeline().addLast("vless-decoder", new VlessRequestDecoder(dispatcher, tag, allowedUsers));
             }
         });

        serverChannel = b.bind(listen, port).sync().channel();
        running = true;
        logger.info("VLESS inbound [{}] listening on {}:{}", tag, listen, port);
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
        logger.info("VLESS inbound [{}] stopped", tag);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static class VlessRequestDecoder extends ByteToMessageDecoder {
        private final Dispatcher dispatcher;
        private final String inboundTag;
        private final Set<UUID> allowedUsers;

        public VlessRequestDecoder(Dispatcher dispatcher, String inboundTag, Set<UUID> allowedUsers) {
            this.dispatcher = dispatcher;
            this.inboundTag = inboundTag;
            this.allowedUsers = allowedUsers;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            VlessHeader header = VlessHeader.decodeRequest(in);
            if (header == null) {
                return; // Wait for more bytes
            }

            if (!allowedUsers.isEmpty() && !allowedUsers.contains(header.getUuid())) {
                logger.warn("[{}] Unauthorized VLESS user UUID: {}", inboundTag, header.getUuid());
                ctx.close();
                return;
            }

            Destination destination = header.getDestination();
            logger.info("[{}] VLESS request verified for user {} -> destination: {}",
                    inboundTag, header.getUuid(), destination);

            // Send VLESS response header (version 0x00, addons 0x00)
            ByteBuf resp = ctx.alloc().buffer(2);
            VlessHeader.encodeResponse(resp);
            ctx.writeAndFlush(resp);

            ByteBuf leftover = null;
            if (in.isReadable()) {
                leftover = in.readRetainedSlice(in.readableBytes());
            }

            // Remove decoder from pipeline
            ctx.pipeline().remove(this);

            ctx.channel().config().setAutoRead(false);

            if (dispatcher != null) {
                dispatcher.dispatch(destination, ctx.channel(), inboundTag);
            } else {
                ctx.close();
            }

            if (leftover != null) {
                ctx.channel().pipeline().fireChannelRead(leftover);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.debug("[{}] VLESS inbound exception: {}", inboundTag, cause.getMessage());
            ctx.close();
        }
    }
}
