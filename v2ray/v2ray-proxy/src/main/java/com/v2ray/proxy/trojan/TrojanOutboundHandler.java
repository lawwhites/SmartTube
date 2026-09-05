package com.v2ray.proxy.trojan;

import com.v2ray.common.net.Destination;
import com.v2ray.common.relay.RelayHandler;
import com.v2ray.proxy.OutboundHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;

/**
 * Trojan Outbound Handler with TLS and SNI support. Connects to a remote Trojan server,
 * writes the Trojan request header, and relays payload bidirectionally.
 * Equivalent to proxy/trojan/client in Go.
 */
public class TrojanOutboundHandler implements OutboundHandler {
    private static final Logger logger = LoggerFactory.getLogger(TrojanOutboundHandler.class);

    private final String tag;
    private final String remoteAddress;
    private final int remotePort;
    private final String hexPasswordHash;
    private final boolean tls;
    private final String sni;
    private final boolean allowInsecure;
    private volatile boolean running = false;

    public TrojanOutboundHandler(String tag, String remoteAddress, int remotePort, String password) {
        this(tag, remoteAddress, remotePort, password, false, null, false);
    }

    public TrojanOutboundHandler(String tag, String remoteAddress, int remotePort, String password,
                                 boolean tls, String sni, boolean allowInsecure) {
        this.tag = tag;
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;
        this.hexPasswordHash = TrojanHeader.computePasswordHash(password);
        this.tls = tls;
        this.sni = sni;
        this.allowInsecure = allowInsecure;
    }

    @Override
    public String getTag() {
        return tag;
    }

    @Override
    public void dispatch(Destination destination, Channel inboundChannel) {
        logger.info("[{}] Connecting to Trojan server {}:{} (TLS={}) for destination {}",
                tag, remoteAddress, remotePort, tls, destination);

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
                 if (tls) {
                     try {
                         SslContextBuilder sslCtxBuilder = SslContextBuilder.forClient();
                         if (allowInsecure) {
                             sslCtxBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
                         }
                         SslContext sslCtx = sslCtxBuilder.build();
                         String peerHost = (sni != null && !sni.isEmpty()) ? sni : remoteAddress;
                         SSLEngine engine = sslCtx.newEngine(ch.alloc(), peerHost, remotePort);
                         ch.pipeline().addLast("ssl", new SslHandler(engine));
                     } catch (Exception e) {
                         logger.error("[{}] Failed to configure SSL: {}", tag, e.getMessage());
                     }
                 }
             }
         });

        b.connect(remoteAddress, remotePort).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                Channel outboundChannel = future.channel();
                logger.debug("[{}] TCP connected to Trojan server", tag);

                SslHandler sslHandler = outboundChannel.pipeline().get(SslHandler.class);
                if (sslHandler != null) {
                    sslHandler.handshakeFuture().addListener((GenericFutureListener<Future<Channel>>) handshakeFuture -> {
                        if (handshakeFuture.isSuccess()) {
                            logger.debug("[{}] TLS handshake successful with {}", tag, (sni != null ? sni : remoteAddress));
                            sendTrojanHeaderAndRelay(outboundChannel, inboundChannel, destination, pendingHandler);
                        } else {
                            logger.warn("[{}] TLS handshake failed: {}", tag, handshakeFuture.cause().getMessage());
                            pendingHandler.releaseAll();
                            RelayHandler.closeOnFlush(inboundChannel);
                        }
                    });
                } else {
                    sendTrojanHeaderAndRelay(outboundChannel, inboundChannel, destination, pendingHandler);
                }
            } else {
                logger.warn("[{}] Failed to connect to Trojan server {}:{}: {}",
                        tag, remoteAddress, remotePort, future.cause().getMessage());
                pendingHandler.releaseAll();
                RelayHandler.closeOnFlush(inboundChannel);
            }
        });
    }

    private void sendTrojanHeaderAndRelay(Channel outboundChannel, Channel inboundChannel,
                                         Destination destination,
                                         com.v2ray.common.relay.PendingBufferHandler pendingHandler) {
        ByteBuf reqBuf = outboundChannel.alloc().buffer(128);
        byte cmd = destination.isUdp() ? TrojanHeader.COMMAND_UDP : TrojanHeader.COMMAND_TCP;
        TrojanHeader header = new TrojanHeader(hexPasswordHash, cmd, destination);
        header.encode(reqBuf);

        outboundChannel.writeAndFlush(reqBuf).addListener((ChannelFutureListener) writeFuture -> {
            if (writeFuture.isSuccess()) {
                // Flush buffered initial payload
                pendingHandler.flushTo(outboundChannel);
                if (inboundChannel.pipeline().get("pending-buffer") != null) {
                    inboundChannel.pipeline().remove("pending-buffer");
                }

                // Setup bidirectional relay
                outboundChannel.pipeline().addLast("trojan->inbound", new RelayHandler(inboundChannel, "trojan->inbound"));
                inboundChannel.pipeline().addLast("inbound->trojan", new RelayHandler(outboundChannel, "inbound->trojan"));

                inboundChannel.config().setAutoRead(true);
                inboundChannel.read();
            } else {
                pendingHandler.releaseAll();
                RelayHandler.closeOnFlush(inboundChannel);
            }
        });
    }

    @Override
    public void start() {
        running = true;
        logger.info("Trojan outbound [{}] started pointing to {}:{} (TLS={})", tag, remoteAddress, remotePort, tls);
    }

    @Override
    public void close() {
        running = false;
        logger.info("Trojan outbound [{}] closed", tag);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
