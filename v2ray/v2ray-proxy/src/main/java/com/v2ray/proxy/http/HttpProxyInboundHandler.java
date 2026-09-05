package com.v2ray.proxy.http;

import com.v2ray.common.dispatcher.Dispatcher;
import com.v2ray.common.net.Destination;
import com.v2ray.proxy.InboundHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * HTTP Proxy Inbound Handler supporting both HTTP CONNECT tunneling (HTTPS)
 * and direct HTTP proxy requests.
 * Equivalent to proxy/http in Go.
 */
public class HttpProxyInboundHandler implements InboundHandler {
    private static final Logger logger = LoggerFactory.getLogger(HttpProxyInboundHandler.class);

    private final String tag;
    private final String listen;
    private final int port;
    private Dispatcher dispatcher;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private boolean ownEventLoopGroups = true;
    private Channel serverChannel;
    private volatile boolean running = false;

    public HttpProxyInboundHandler(String tag, String listen, int port) {
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
                 ch.pipeline().addLast("http-codec", new HttpServerCodec());
                 ch.pipeline().addLast("http-handler", new HttpProxyServerHandler(dispatcher, tag));
             }
         });

        serverChannel = b.bind(listen, port).sync().channel();
        running = true;
        logger.info("HTTP proxy inbound [{}] listening on {}:{}", tag, listen, port);
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
        logger.info("HTTP proxy inbound [{}] stopped", tag);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static class HttpProxyServerHandler extends SimpleChannelInboundHandler<HttpObject> {
        private final Dispatcher dispatcher;
        private final String inboundTag;

        public HttpProxyServerHandler(Dispatcher dispatcher, String inboundTag) {
            this.dispatcher = dispatcher;
            this.inboundTag = inboundTag;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
            if (msg instanceof HttpRequest) {
                HttpRequest req = (HttpRequest) msg;

                if (HttpMethod.CONNECT.equals(req.method())) {
                    // HTTP CONNECT method for HTTPS tunneling
                    String uri = req.uri();
                    String[] parts = uri.split(":");
                    String host = parts[0];
                    int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 443;
                    Destination destination = Destination.tcp(host, port);

                    logger.info("[{}] Received HTTP CONNECT to {}", inboundTag, destination);

                    FullHttpResponse response = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            new HttpResponseStatus(200, "Connection Established")
                    );

                    ctx.writeAndFlush(response).addListener((ChannelFutureListener) future -> {
                        if (future.isSuccess()) {
                            // Strip HTTP codecs to turn pipeline into a raw byte stream
                            ctx.pipeline().remove("http-codec");
                            ctx.pipeline().remove(this);

                            ctx.channel().config().setAutoRead(false);

                            if (dispatcher != null) {
                                dispatcher.dispatch(destination, ctx.channel(), inboundTag);
                            } else {
                                ctx.close();
                            }
                        } else {
                            ctx.close();
                        }
                    });
                } else {
                    // Standard HTTP request: parse host from URI or Host header
                    String hostHeader = req.headers().get(HttpHeaderNames.HOST);
                    String host;
                    int port = 80;

                    if (hostHeader != null && !hostHeader.isEmpty()) {
                        String[] parts = hostHeader.split(":");
                        host = parts[0];
                        if (parts.length > 1) {
                            try {
                                port = Integer.parseInt(parts[1]);
                            } catch (NumberFormatException ignored) {}
                        }
                    } else {
                        try {
                            URI uri = new URI(req.uri());
                            host = uri.getHost();
                            port = uri.getPort() > 0 ? uri.getPort() : 80;
                        } catch (Exception e) {
                            ctx.close();
                            return;
                        }
                    }

                    if (host == null) {
                        ctx.close();
                        return;
                    }

                    Destination destination = Destination.tcp(host, port);
                    logger.info("[{}] Received plain HTTP request to {}", inboundTag, destination);

                    // For plain HTTP, remove HTTP server codec and let raw relay handle it
                    ctx.pipeline().remove("http-codec");
                    ctx.pipeline().remove(this);

                    ctx.channel().config().setAutoRead(false);

                    if (dispatcher != null) {
                        dispatcher.dispatch(destination, ctx.channel(), inboundTag);
                    } else {
                        ctx.close();
                    }
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.debug("[{}] HTTP proxy error: {}", inboundTag, cause.getMessage());
            ctx.close();
        }
    }
}
