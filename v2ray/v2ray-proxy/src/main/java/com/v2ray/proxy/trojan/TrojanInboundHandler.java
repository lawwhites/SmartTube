package com.v2ray.proxy.trojan;

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

/**
 * Trojan Inbound Handler.
 * Validates Trojan password hashes, decodes targets, and forwards to Dispatcher.
 * Equivalent to proxy/trojan/server in Go.
 */
public class TrojanInboundHandler implements InboundHandler {
    private static final Logger logger = LoggerFactory.getLogger(TrojanInboundHandler.class);

    private final String tag;
    private final String listen;
    private final int port;
    private final Set<String> validPasswordHashes = new HashSet<>();
    private Dispatcher dispatcher;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private boolean ownEventLoopGroups = true;
    private Channel serverChannel;
    private volatile boolean running = false;

    public TrojanInboundHandler(String tag, String listen, int port) {
        this.tag = tag;
        this.listen = (listen == null || listen.isEmpty()) ? "0.0.0.0" : listen;
        this.port = port;
    }

    public void addPassword(String password) {
        validPasswordHashes.add(TrojanHeader.computePasswordHash(password));
    }

    public void addPasswordHash(String hash) {
        validPasswordHashes.add(hash.toLowerCase());
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

    @Override
    public void setEventLoopGroups(EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.ownEventLoopGroups = false;
    }

    private io.netty.handler.ssl.SslContext sslContext;

    public void setSslContext(io.netty.handler.ssl.SslContext sslContext) {
        this.sslContext = sslContext;
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
                 ch.pipeline().addLast("trojan-decoder", new TrojanRequestDecoder(dispatcher, tag, validPasswordHashes));
             }
         });

        serverChannel = b.bind(listen, port).sync().channel();
        running = true;
        logger.info("Trojan inbound [{}] listening on {}:{}", tag, listen, port);
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
        logger.info("Trojan inbound [{}] stopped", tag);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static class TrojanRequestDecoder extends ByteToMessageDecoder {
        private final Dispatcher dispatcher;
        private final String inboundTag;
        private final Set<String> validHashes;

        public TrojanRequestDecoder(Dispatcher dispatcher, String inboundTag, Set<String> validHashes) {
            this.dispatcher = dispatcher;
            this.inboundTag = inboundTag;
            this.validHashes = validHashes;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            TrojanHeader header = TrojanHeader.decode(in);
            if (header == null) {
                return;
            }

            if (!validHashes.isEmpty() && !validHashes.contains(header.getHexPasswordHash().toLowerCase())) {
                logger.warn("[{}] Unauthorized Trojan password hash: {}", inboundTag, header.getHexPasswordHash());
                ctx.close();
                return;
            }

            Destination destination = header.getDestination();
            logger.info("[{}] Trojan request verified for destination: {}", inboundTag, destination);

            ByteBuf leftover = null;
            if (in.isReadable()) {
                leftover = in.readRetainedSlice(in.readableBytes());
            }

            // Strip decoder
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
            logger.debug("[{}] Trojan inbound exception: {}", inboundTag, cause.getMessage());
            ctx.close();
        }
    }
}
