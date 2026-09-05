package com.v2ray.proxy.vmess;

import com.v2ray.common.dispatcher.Dispatcher;
import com.v2ray.common.net.Destination;
import com.v2ray.proxy.InboundHandler;
import com.v2ray.proxy.vmess.aead.VMessAead;
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

import java.util.*;

/**
 * VMess Inbound Handler. Supports VMess AEAD protocol authentication,
 * request parsing, response header generation, and chunk stream forwarding.
 */
public class VmessInboundHandler implements InboundHandler {
    private static final Logger logger = LoggerFactory.getLogger(VmessInboundHandler.class);

    private final String tag;
    private final String listen;
    private final int port;
    private final Map<UUID, byte[]> allowedUsers = new HashMap<>();
    private Dispatcher dispatcher;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private boolean ownEventLoopGroups = true;
    private Channel serverChannel;
    private volatile boolean running = false;

    public VmessInboundHandler(String tag, String listen, int port) {
        this.tag = tag;
        this.listen = (listen == null || listen.isEmpty()) ? "0.0.0.0" : listen;
        this.port = port;
    }

    public void addAllowedUser(UUID uuid) {
        allowedUsers.put(uuid, VMessAead.cmdKey(uuid));
    }

    @Override
    public String getTag() {
        return tag;
    }

    @Override
    public int getPort() {
        if (serverChannel != null && serverChannel.localAddress() instanceof java.net.InetSocketAddress) {
            return ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
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
                 ch.pipeline().addLast("vmess-inbound-decoder", new VmessRequestDecoder(dispatcher, tag, allowedUsers));
             }
         });

        serverChannel = b.bind(listen, port).sync().channel();
        running = true;
        logger.info("VMess inbound [{}] listening on {}:{}", tag, listen, port);
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
        logger.info("VMess inbound [{}] stopped", tag);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static class VmessRequestDecoder extends ByteToMessageDecoder {
        private final Dispatcher dispatcher;
        private final String inboundTag;
        private final Map<UUID, byte[]> allowedUsers;

        private UUID authenticatedUser = null;
        private byte[] authenticatedCmdKey = null;
        private byte[] authId = null;
        private byte[] lenEncrypted = null;
        private byte[] nonce = null;
        private int payloadLen = -1;

        public VmessRequestDecoder(Dispatcher dispatcher, String inboundTag, Map<UUID, byte[]> allowedUsers) {
            this.dispatcher = dispatcher;
            this.inboundTag = inboundTag;
            this.allowedUsers = allowedUsers;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            // Header: authId (16) + lenEncrypted (18) + nonce (8) = 42 bytes minimum prefix
            if (payloadLen == -1) {
                if (in.readableBytes() < 42) {
                    return;
                }

                in.markReaderIndex();
                byte[] currentAuthId = new byte[16];
                in.readBytes(currentAuthId);

                // Authenticate user by authId
                for (Map.Entry<UUID, byte[]> entry : allowedUsers.entrySet()) {
                    try {
                        VMessAead.openAuthId(entry.getValue(), currentAuthId);
                        authenticatedUser = entry.getKey();
                        authenticatedCmdKey = entry.getValue();
                        break;
                    } catch (Exception ignored) {
                    }
                }

                if (authenticatedCmdKey == null) {
                    logger.warn("[{}] Unauthorized VMess request, dropping connection", inboundTag);
                    ctx.close();
                    return;
                }

                authId = currentAuthId;
                lenEncrypted = new byte[18];
                in.readBytes(lenEncrypted);

                nonce = new byte[8];
                in.readBytes(nonce);

                try {
                    byte[] lenKey = com.v2ray.proxy.vmess.aead.VMessKdf.kdf16(authenticatedCmdKey,
                            com.v2ray.proxy.vmess.aead.VMessKdf.KDF_SALT_VMESS_HEADER_PAYLOAD_LENGTH_AEAD_KEY, authId, nonce);
                    byte[] lenNonce = Arrays.copyOf(com.v2ray.proxy.vmess.aead.VMessKdf.kdf(authenticatedCmdKey,
                            com.v2ray.proxy.vmess.aead.VMessKdf.KDF_SALT_VMESS_HEADER_PAYLOAD_LENGTH_AEAD_IV, authId, nonce), 12);
                    byte[] lenBytes = VMessAead.aesGcmDecrypt(lenKey, lenNonce, lenEncrypted, authId);
                    payloadLen = ((lenBytes[0] & 0xFF) << 8) | (lenBytes[1] & 0xFF);
                } catch (Exception e) {
                    logger.warn("[{}] Failed to decrypt VMess header length: {}", inboundTag, e.getMessage());
                    ctx.close();
                    return;
                }
            }

            int requiredBytes = payloadLen + 16;
            if (in.readableBytes() < requiredBytes) {
                return;
            }

            byte[] payloadEncrypted = new byte[requiredBytes];
            in.readBytes(payloadEncrypted);

            byte[] payloadPlain;
            try {
                byte[] payloadKey = com.v2ray.proxy.vmess.aead.VMessKdf.kdf16(authenticatedCmdKey,
                        com.v2ray.proxy.vmess.aead.VMessKdf.KDF_SALT_VMESS_HEADER_PAYLOAD_AEAD_KEY, authId, nonce);
                byte[] payloadNonce = Arrays.copyOf(com.v2ray.proxy.vmess.aead.VMessKdf.kdf(authenticatedCmdKey,
                        com.v2ray.proxy.vmess.aead.VMessKdf.KDF_SALT_VMESS_HEADER_PAYLOAD_AEAD_IV, authId, nonce), 12);
                payloadPlain = VMessAead.aesGcmDecrypt(payloadKey, payloadNonce, payloadEncrypted, authId);
            } catch (Exception e) {
                logger.warn("[{}] Failed to decrypt VMess header payload: {}", inboundTag, e.getMessage());
                ctx.close();
                return;
            }

            VMessHeader.RequestHeader req = VMessHeader.decodeRequestPayload(payloadPlain);
            logger.info("[{}] Authenticated VMess user {} -> destination {}", inboundTag, authenticatedUser, req.destination);

            byte[] respBodyKey = req.responseBodyKey();
            byte[] respBodyIV = req.responseBodyIV();

            // Send response header
            byte[] respPlain = new byte[]{req.responseHeader, 0, 0, 0};
            byte[] respSealed = VMessAead.sealResponseHeader(respBodyKey, respBodyIV, respPlain);
            ctx.writeAndFlush(Unpooled.wrappedBuffer(respSealed));

            boolean chunkMasking = (req.option & VMessHeader.OPTION_CHUNK_MASKING) != 0;

            ByteBuf leftover = null;
            if (in.isReadable()) {
                leftover = in.readRetainedSlice(in.readableBytes());
            }

            ChannelPipeline pipeline = ctx.pipeline();
            pipeline.remove(this);

            pipeline.addLast("vmess-chunk-decoder", new VMessChunkCodec.ChunkDecoder(req.requestBodyKey, req.requestBodyIV, chunkMasking));
            pipeline.addLast("vmess-chunk-encoder", new VMessChunkCodec.ChunkEncoder(respBodyKey, respBodyIV, chunkMasking));

            ctx.channel().config().setAutoRead(false);

            if (dispatcher != null) {
                dispatcher.dispatch(req.destination, ctx.channel(), inboundTag);
            } else {
                ctx.close();
            }

            if (leftover != null) {
                ctx.channel().pipeline().fireChannelRead(leftover);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.debug("[{}] VMess inbound exception: {}", inboundTag, cause.getMessage());
            ctx.close();
        }
    }
}
