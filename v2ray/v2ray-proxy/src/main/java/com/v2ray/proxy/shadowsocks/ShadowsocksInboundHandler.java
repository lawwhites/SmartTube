package com.v2ray.proxy.shadowsocks;

import com.v2ray.common.dispatcher.Dispatcher;
import com.v2ray.common.net.Destination;
import com.v2ray.common.net.Network;
import com.v2ray.proxy.InboundHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

/**
 * Shadowsocks AEAD Inbound Handler (SIP008).
 */
public class ShadowsocksInboundHandler implements InboundHandler {
    private static final Logger logger = LoggerFactory.getLogger(ShadowsocksInboundHandler.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String tag;
    private final String listen;
    private final int port;
    private final String method;
    private final byte[] masterKey;
    private final int keySize;
    private final int saltSize;
    private Dispatcher dispatcher;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private boolean ownEventLoopGroups = true;
    private Channel serverChannel;
    private volatile boolean running = false;

    public ShadowsocksInboundHandler(String tag, String listen, int port, String method, String password) {
        this.tag = tag;
        this.listen = (listen == null || listen.isEmpty()) ? "0.0.0.0" : listen;
        this.port = port;
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
                 ch.pipeline().addLast("ss-salt-decoder", new ShadowsocksRequestDecoder(dispatcher, tag, method, masterKey, keySize, saltSize));
             }
         });

        serverChannel = b.bind(listen, port).sync().channel();
        running = true;
        logger.info("Shadowsocks inbound [{}] listening on {}:{} with method {}", tag, listen, getPort(), method);
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
        logger.info("Shadowsocks inbound [{}] stopped", tag);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static class ShadowsocksRequestDecoder extends ByteToMessageDecoder {
        private final Dispatcher dispatcher;
        private final String inboundTag;
        private final String method;
        private final byte[] masterKey;
        private final int keySize;
        private final int saltSize;

        private byte[] clientSubkey = null;

        public ShadowsocksRequestDecoder(Dispatcher dispatcher, String inboundTag, String method,
                                         byte[] masterKey, int keySize, int saltSize) {
            this.dispatcher = dispatcher;
            this.inboundTag = inboundTag;
            this.method = method;
            this.masterKey = masterKey;
            this.keySize = keySize;
            this.saltSize = saltSize;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (clientSubkey == null) {
                if (in.readableBytes() < saltSize) {
                    return;
                }
                byte[] clientSalt = new byte[saltSize];
                in.readBytes(clientSalt);
                clientSubkey = ShadowsocksCrypto.deriveSubkey(masterKey, clientSalt, keySize);

                // Add chunk decoder
                ctx.pipeline().addAfter(ctx.name(), "ss-chunk-decoder", new ShadowsocksChunkCodec.Decoder(method, clientSubkey));
                ctx.pipeline().addAfter("ss-chunk-decoder", "ss-target-parser", new TargetAddressParser(dispatcher, inboundTag, method, masterKey, keySize, saltSize));
                ctx.pipeline().remove(this);
            }
        }
    }

    private static class TargetAddressParser extends ChannelInboundHandlerAdapter {
        private final Dispatcher dispatcher;
        private final String inboundTag;
        private final String method;
        private final byte[] masterKey;
        private final int keySize;
        private final int saltSize;

        public TargetAddressParser(Dispatcher dispatcher, String inboundTag, String method,
                                   byte[] masterKey, int keySize, int saltSize) {
            this.dispatcher = dispatcher;
            this.inboundTag = inboundTag;
            this.method = method;
            this.masterKey = masterKey;
            this.keySize = keySize;
            this.saltSize = saltSize;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof ByteBuf)) {
                super.channelRead(ctx, msg);
                return;
            }

            ByteBuf buf = (ByteBuf) msg;
            try {
                if (buf.readableBytes() < 2) {
                    buf.release();
                    ctx.close();
                    return;
                }

                byte addrType = buf.readByte();
                String host;
                if (addrType == 0x01) { // IPv4
                    if (buf.readableBytes() < 6) {
                        buf.release();
                        ctx.close();
                        return;
                    }
                    byte[] ip = new byte[4];
                    buf.readBytes(ip);
                    host = InetAddress.getByAddress(ip).getHostAddress();
                } else if (addrType == 0x03) { // Domain
                    int domainLen = buf.readUnsignedByte();
                    if (buf.readableBytes() < domainLen + 2) {
                        buf.release();
                        ctx.close();
                        return;
                    }
                    byte[] domainBytes = new byte[domainLen];
                    buf.readBytes(domainBytes);
                    host = new String(domainBytes, StandardCharsets.UTF_8);
                } else if (addrType == 0x04) { // IPv6
                    if (buf.readableBytes() < 18) {
                        buf.release();
                        ctx.close();
                        return;
                    }
                    byte[] ip = new byte[16];
                    buf.readBytes(ip);
                    host = InetAddress.getByAddress(ip).getHostAddress();
                } else {
                    logger.warn("[{}] Unknown address type in Shadowsocks: {}", inboundTag, addrType);
                    buf.release();
                    ctx.close();
                    return;
                }

                int destPort = buf.readUnsignedShort();
                Destination destination = Destination.tcp(host, destPort);
                logger.info("[{}] Shadowsocks target destination: {}", inboundTag, destination);

                // Prepare server salt and encoder
                byte[] serverSalt = new byte[saltSize];
                RANDOM.nextBytes(serverSalt);
                byte[] serverSubkey = ShadowsocksCrypto.deriveSubkey(masterKey, serverSalt, keySize);

                // Send server salt
                ctx.writeAndFlush(Unpooled.wrappedBuffer(serverSalt));

                // Add chunk encoder for responses
                ctx.pipeline().addBefore("ss-chunk-decoder", "ss-chunk-encoder", new ShadowsocksChunkCodec.Encoder(method, serverSubkey));

                ByteBuf leftover = null;
                if (buf.isReadable()) {
                    leftover = buf.readRetainedSlice(buf.readableBytes());
                }

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
            } finally {
                buf.release();
            }
        }
    }
}
