package com.v2ray.proxy.vless;

import com.v2ray.common.net.Destination;
import com.v2ray.common.relay.RelayHandler;
import com.v2ray.proxy.OutboundHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * VLESS Outbound Handler. Connects to a remote VLESS server, sends VLESS request header,
 * receives VLESS response header, and establishes a bidirectional relay.
 * Equivalent to proxy/vless/outbound in Go.
 */
public class VlessOutboundHandler implements OutboundHandler {
    private static final Logger logger = LoggerFactory.getLogger(VlessOutboundHandler.class);

    private final String tag;
    private final String remoteAddress;
    private final int remotePort;
    private final UUID uuid;
    private volatile boolean running = false;

    public VlessOutboundHandler(String tag, String remoteAddress, int remotePort, UUID uuid) {
        this.tag = tag;
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;
        this.uuid = uuid;
    }

    @Override
    public String getTag() {
        return tag;
    }

    @Override
    public void dispatch(Destination destination, Channel inboundChannel) {
        logger.info("[{}] Connecting to VLESS server {}:{} for destination {}",
                tag, remoteAddress, remotePort, destination);

        Bootstrap b = new Bootstrap();
        b.group(inboundChannel.eventLoop())
         .channel(com.v2ray.common.net.TransportHelper.socketChannelClass(inboundChannel));
        com.v2ray.common.net.TransportHelper.applyClientOptions(b);
        b.handler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 ch.pipeline().addLast("vless-response-decoder", new ByteToMessageDecoder() {
                     @Override
                     protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                         if (in.readableBytes() < 2) {
                             return; // Need version (1) + addon len (1)
                         }
                         byte version = in.readByte();
                         int addonLen = in.readUnsignedByte();
                         if (in.readableBytes() < addonLen) {
                             in.readerIndex(in.readerIndex() - 2);
                             return;
                         }
                         in.skipBytes(addonLen);

                         logger.debug("[{}] Received VLESS response header from server, version: {}", tag, version);

                         // Response header consumed, remove decoder
                         ctx.pipeline().remove(this);

                         // Hook up bidirectional relay
                         ctx.pipeline().addLast("vless->inbound", new RelayHandler(inboundChannel, "vless->inbound"));
                         inboundChannel.pipeline().addLast("inbound->vless", new RelayHandler(ctx.channel(), "inbound->vless"));

                         inboundChannel.config().setAutoRead(true);
                         inboundChannel.read();

                         if (in.isReadable()) {
                             inboundChannel.writeAndFlush(in.readRetainedSlice(in.readableBytes()));
                         }
                     }
                 });
             }
         });

        b.connect(remoteAddress, remotePort).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                Channel outboundChannel = future.channel();
                logger.debug("[{}] Connected to remote VLESS server", tag);

                // Send VLESS request header
                ByteBuf reqBuf = outboundChannel.alloc().buffer(64);
                byte cmd = destination.isUdp() ? VlessHeader.COMMAND_UDP : VlessHeader.COMMAND_TCP;
                VlessHeader header = new VlessHeader(uuid, cmd, destination);
                header.encodeRequest(reqBuf);

                outboundChannel.writeAndFlush(reqBuf);
            } else {
                logger.warn("[{}] Failed to connect to VLESS server {}:{}: {}",
                        tag, remoteAddress, remotePort, future.cause().getMessage());
                RelayHandler.closeOnFlush(inboundChannel);
            }
        });
    }

    @Override
    public void start() {
        running = true;
        logger.info("VLESS outbound [{}] started pointing to {}:{}", tag, remoteAddress, remotePort);
    }

    @Override
    public void close() {
        running = false;
        logger.info("VLESS outbound [{}] closed", tag);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
