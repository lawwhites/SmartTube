package com.v2ray.proxy.shadowsocks;

import com.v2ray.common.net.Destination;
import com.v2ray.common.relay.PendingBufferHandler;
import com.v2ray.common.relay.RelayHandler;
import com.v2ray.proxy.OutboundHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.NetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

/**
 * Shadowsocks AEAD Outbound Handler (SIP008).
 */
public class ShadowsocksOutboundHandler implements OutboundHandler {
    private static final Logger logger = LoggerFactory.getLogger(ShadowsocksOutboundHandler.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String tag;
    private final String remoteAddress;
    private final int remotePort;
    private final String method;
    private final byte[] masterKey;
    private final int keySize;
    private final int saltSize;
    private volatile boolean running = false;

    public ShadowsocksOutboundHandler(String tag, String remoteAddress, int remotePort, String method, String password) {
        this.tag = tag;
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;
        this.method = method;
        this.keySize = ShadowsocksCrypto.getKeySize(method);
        this.saltSize = ShadowsocksCrypto.getSaltSize(method);
        this.masterKey = ShadowsocksCrypto.passwordToKey(password, keySize);
    }

    @Override
    public String getTag() {
        return tag;
    }

    @Override
    public void dispatch(Destination destination, Channel inboundChannel) {
        logger.info("[{}] Connecting to Shadowsocks server {}:{} for destination {}",
                tag, remoteAddress, remotePort, destination);

        PendingBufferHandler pendingHandler = new PendingBufferHandler();
        inboundChannel.pipeline().addLast("pending-buffer", pendingHandler);

        byte[] clientSalt = new byte[saltSize];
        RANDOM.nextBytes(clientSalt);
        byte[] clientSubkey = ShadowsocksCrypto.deriveSubkey(masterKey, clientSalt, keySize);

        Bootstrap b = new Bootstrap();
        b.group(inboundChannel.eventLoop())
         .channel(com.v2ray.common.net.TransportHelper.socketChannelClass(inboundChannel));
        com.v2ray.common.net.TransportHelper.applyClientOptions(b);
        b.handler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 ch.pipeline().addLast("ss-server-salt-decoder", new ByteToMessageDecoder() {
                     @Override
                     protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                         if (in.readableBytes() < saltSize) {
                             return;
                         }

                         byte[] serverSalt = new byte[saltSize];
                         in.readBytes(serverSalt);
                         byte[] serverSubkey = ShadowsocksCrypto.deriveSubkey(masterKey, serverSalt, keySize);

                         ByteBuf leftover = null;
                         if (in.isReadable()) {
                             leftover = in.readRetainedSlice(in.readableBytes());
                         }

                         ctx.pipeline().remove(this);

                         // Add chunk decoder to decrypt server responses
                         ctx.pipeline().addLast("ss-chunk-decoder", new ShadowsocksChunkCodec.Decoder(method, serverSubkey));

                         // Relay
                         ctx.pipeline().addLast("ss->inbound", new RelayHandler(inboundChannel, "ss->inbound"));
                         inboundChannel.pipeline().addLast("inbound->ss", new RelayHandler(ctx.channel(), "inbound->ss"));

                         pendingHandler.flushTo(ctx.channel());

                         inboundChannel.config().setAutoRead(true);
                         inboundChannel.read();

                         if (leftover != null) {
                             ctx.channel().pipeline().fireChannelRead(leftover);
                         }
                     }
                 });
             }
         });

        b.connect(remoteAddress, remotePort).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                Channel outboundChannel = future.channel();
                logger.debug("[{}] Connected to Shadowsocks server", tag);

                // 1. Send client salt as raw bytes
                outboundChannel.writeAndFlush(Unpooled.wrappedBuffer(clientSalt));

                // 2. Add chunk encoder for all subsequent outbound data
                outboundChannel.pipeline().addLast("ss-chunk-encoder", new ShadowsocksChunkCodec.Encoder(method, clientSubkey));

                // 3. Send SOCKS5 target address (which gets encoded by the encoder into the first chunk)
                ByteBuf addrBuf = outboundChannel.alloc().buffer(32);
                encodeDestination(destination, addrBuf);
                outboundChannel.writeAndFlush(addrBuf);
            } else {
                logger.warn("[{}] Failed to connect to Shadowsocks server {}:{}: {}",
                        tag, remoteAddress, remotePort, future.cause().getMessage());
                RelayHandler.closeOnFlush(inboundChannel);
            }
        });
    }

    private void encodeDestination(Destination dest, ByteBuf out) {
        String host = dest.getAddress();
        try {
            if (NetUtil.isValidIpV4Address(host)) {
                out.writeByte(0x01);
                out.writeBytes(InetAddress.getByName(host).getAddress());
            } else if (NetUtil.isValidIpV6Address(host)) {
                out.writeByte(0x04);
                out.writeBytes(InetAddress.getByName(host).getAddress());
            } else {
                out.writeByte(0x03);
                byte[] domainBytes = host.getBytes(StandardCharsets.UTF_8);
                out.writeByte(domainBytes.length);
                out.writeBytes(domainBytes);
            }
            out.writeShort(dest.getPort());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void start() {
        running = true;
        logger.info("Shadowsocks outbound [{}] started pointing to {}:{}", tag, remoteAddress, remotePort);
    }

    @Override
    public void close() {
        running = false;
        logger.info("Shadowsocks outbound [{}] closed", tag);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
