package com.v2ray.proxy.socks;

import com.v2ray.common.dispatcher.Dispatcher;
import com.v2ray.common.net.Destination;
import com.v2ray.proxy.InboundHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.socksx.v5.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SOCKS5 Inbound Handler implementing RFC 1928 SOCKS protocol.
 * Equivalent to proxy/socks in Go.
 */
public class Socks5InboundHandler implements InboundHandler {
    private static final Logger logger = LoggerFactory.getLogger(Socks5InboundHandler.class);

    private final String tag;
    private final String listen;
    private final int port;
    private Dispatcher dispatcher;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private boolean ownEventLoopGroups = true;
    private Channel serverChannel;
    private volatile boolean running = false;

    public Socks5InboundHandler(String tag, String listen, int port) {
        this.tag = tag;
        this.listen = (listen == null || listen.isEmpty()) ? "0.0.0.0" : listen;
        this.port = port;
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
                 ch.pipeline().addLast("socks5-encoder", Socks5ServerEncoder.DEFAULT);
                 ch.pipeline().addLast("socks5-initial-decoder", new Socks5InitialRequestDecoder());
                 ch.pipeline().addLast("socks5-handler", new Socks5ChannelHandler(dispatcher, tag));
             }
         });

        serverChannel = b.bind(listen, port).sync().channel();
        running = true;
        logger.info("Socks5 inbound [{}] listening on {}:{}", tag, listen, port);
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
        logger.info("Socks5 inbound [{}] stopped", tag);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static class Socks5ChannelHandler extends SimpleChannelInboundHandler<Socks5Message> {
        private final Dispatcher dispatcher;
        private final String inboundTag;

        public Socks5ChannelHandler(Dispatcher dispatcher, String inboundTag) {
            this.dispatcher = dispatcher;
            this.inboundTag = inboundTag;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Socks5Message msg) {
            if (msg instanceof Socks5InitialRequest) {
                // SOCKS5 method negotiation
                ctx.pipeline().addFirst("socks5-command-decoder", new Socks5CommandRequestDecoder());
                ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));
            } else if (msg instanceof Socks5CommandRequest) {
                Socks5CommandRequest req = (Socks5CommandRequest) msg;
                if (req.type() == Socks5CommandType.CONNECT) {
                    Destination destination = Destination.tcp(req.dstAddr(), req.dstPort());
                    logger.info("[{}] Received SOCKS5 CONNECT to {}", inboundTag, destination);

                    // Send success response. Always reply with a fixed IPv4 0.0.0.0:0
                    // instead of echoing the request's domain: Android's JDK
                    // SocksSocketImpl misparses DOMAIN-type replies and leaves
                    // trailing bytes in the stream, which breaks the TLS layer
                    // ("Unable to parse TLS packet header").
                    ctx.channel().writeAndFlush(new DefaultSocks5CommandResponse(
                            Socks5CommandStatus.SUCCESS,
                            Socks5AddressType.IPv4,
                            "0.0.0.0",
                            0
                    )).addListener((ChannelFutureListener) future -> {
                        if (future.isSuccess()) {
                            // Remove SOCKS codecs from pipeline so it becomes a transparent byte stream
                            ctx.pipeline().remove(Socks5ServerEncoder.class);
                            ctx.pipeline().remove(Socks5CommandRequestDecoder.class);
                            ctx.pipeline().remove(this);

                            // Temporarily stop autoRead until outbound connection establishes
                            ctx.channel().config().setAutoRead(false);

                            if (dispatcher != null) {
                                dispatcher.dispatch(destination, ctx.channel(), inboundTag);
                            } else {
                                logger.error("Dispatcher is null, closing connection");
                                ctx.close();
                            }
                        } else {
                            ctx.close();
                        }
                    });
                } else {
                    logger.warn("[{}] Unsupported SOCKS5 command: {}", inboundTag, req.type());
                    ctx.channel().writeAndFlush(new DefaultSocks5CommandResponse(
                            Socks5CommandStatus.COMMAND_UNSUPPORTED,
                            req.dstAddrType()
                    )).addListener(ChannelFutureListener.CLOSE);
                }
            } else {
                ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.debug("[{}] Inbound connection error: {}", inboundTag, cause.getMessage());
            ctx.close();
        }
    }
}
