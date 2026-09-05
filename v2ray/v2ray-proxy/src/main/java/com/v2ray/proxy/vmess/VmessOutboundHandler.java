package com.v2ray.proxy.vmess;

import com.v2ray.common.net.Destination;
import com.v2ray.common.relay.PendingBufferHandler;
import com.v2ray.common.relay.RelayHandler;
import com.v2ray.proxy.OutboundHandler;
import com.v2ray.proxy.vmess.aead.VMessAead;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * VMess Outbound Handler. Connects to a remote VMess server, sends VMess AEAD request header,
 * receives response header, and establishes bidirectional chunk relay.
 */
public class VmessOutboundHandler implements OutboundHandler {
    private static final Logger logger = LoggerFactory.getLogger(VmessOutboundHandler.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String tag;
    private final String remoteAddress;
    private final int remotePort;
    private final UUID uuid;
    private final byte[] cmdKey;
    private volatile boolean running = false;

    public VmessOutboundHandler(String tag, String remoteAddress, int remotePort, UUID uuid) {
        this.tag = tag;
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;
        this.uuid = uuid;
        this.cmdKey = VMessAead.cmdKey(uuid);
    }

    @Override
    public String getTag() {
        return tag;
    }

    @Override
    public void dispatch(Destination destination, Channel inboundChannel) {
        logger.info("[{}] Connecting to VMess server {}:{} for destination {}",
                tag, remoteAddress, remotePort, destination);

        PendingBufferHandler pendingHandler = new PendingBufferHandler();
        inboundChannel.pipeline().addLast("pending-buffer", pendingHandler);

        VMessHeader.RequestHeader req = new VMessHeader.RequestHeader();
        RANDOM.nextBytes(req.requestBodyIV);
        RANDOM.nextBytes(req.requestBodyKey);
        byte[] rHead = new byte[1];
        RANDOM.nextBytes(rHead);
        req.responseHeader = rHead[0];
        req.destination = destination;
        req.userUuid = uuid;
        req.command = destination.isUdp() ? VMessHeader.CMD_UDP : VMessHeader.CMD_TCP;

        byte[] plainPayload = VMessHeader.encodeRequestPayload(req);
        byte[] sealedHeader = VMessAead.sealHeader(cmdKey, plainPayload);

        byte[] respBodyKey = req.responseBodyKey();
        byte[] respBodyIV = req.responseBodyIV();
        boolean chunkMasking = (req.option & VMessHeader.OPTION_CHUNK_MASKING) != 0;

        Bootstrap b = new Bootstrap();
        b.group(inboundChannel.eventLoop())
         .channel(com.v2ray.common.net.TransportHelper.socketChannelClass(inboundChannel));
        com.v2ray.common.net.TransportHelper.applyClientOptions(b);
        b.handler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 ch.pipeline().addLast("vmess-resp-decoder", new ByteToMessageDecoder() {
                     private int expectedRespLen = -1;

                     @Override
                     protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                         if (expectedRespLen == -1) {
                             if (in.readableBytes() < 18) {
                                 return; // Need 18-byte length ciphertext
                             }
                             byte[] lenEnc = new byte[18];
                             in.readBytes(lenEnc);
                             expectedRespLen = VMessAead.openResponseHeaderLength(respBodyKey, respBodyIV, lenEnc);
                         }

                         int totalRespLen = expectedRespLen + 16;
                         if (in.readableBytes() < totalRespLen) {
                             return;
                         }

                         byte[] respPayloadEnc = new byte[totalRespLen];
                         in.readBytes(respPayloadEnc);

                         byte[] respPlain = VMessAead.openResponseHeaderPayload(respBodyKey, respBodyIV, respPayloadEnc);
                         if (respPlain[0] != req.responseHeader) {
                             logger.warn("[{}] VMess response header check failed", tag);
                             ctx.close();
                             return;
                         }

                         logger.debug("[{}] VMess response header verified successfully", tag);
                         if (logger.isDebugEnabled()) {
                             StringBuilder sb = new StringBuilder();
                             for (byte b : respPlain) {
                                 sb.append(String.format("%02x ", b));
                             }
                             logger.debug("[{}] VMess response payload: {}", tag, sb.toString().trim());
                         }

                         ByteBuf leftover = null;
                         if (in.isReadable()) {
                             leftover = in.readRetainedSlice(in.readableBytes());
                         }

                         // Remove response header decoder
                         ctx.pipeline().remove(this);

                         // Add chunk decoder to decrypt server responses
                         ctx.pipeline().addLast("vmess-chunk-decoder", new VMessChunkCodec.ChunkDecoder(respBodyKey, respBodyIV, chunkMasking));

                         // Relay server -> client (client -> server relay was set up at connect time)
                         ctx.pipeline().addLast("vmess->inbound", new RelayHandler(inboundChannel, "vmess->inbound"));

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
                logger.debug("[{}] Connected to remote VMess server", tag);

                // Send request header as raw bytes first
                outboundChannel.writeAndFlush(Unpooled.wrappedBuffer(sealedHeader));

                // Add chunk encoder for subsequent client data
                outboundChannel.pipeline().addLast("vmess-chunk-encoder",
                        new VMessChunkCodec.ChunkEncoder(req.requestBodyKey, req.requestBodyIV, chunkMasking));

                // Flush the client's pending data immediately. Do NOT wait for the
                // response header: real servers (Xray 26.x) write it lazily, only
                // when the first downstream data arrives, so waiting here would
                // deadlock client-first protocols like TLS.
                inboundChannel.pipeline().addLast("inbound->vmess", new RelayHandler(outboundChannel, "inbound->vmess"));
                pendingHandler.flushTo(outboundChannel);
                inboundChannel.config().setAutoRead(true);
                inboundChannel.read();
            } else {
                logger.warn("[{}] Failed to connect to VMess server {}:{}",
                        tag, remoteAddress, remotePort, future.cause());
                RelayHandler.closeOnFlush(inboundChannel);
            }
        });
    }

    @Override
    public void start() {
        running = true;
        logger.info("VMess outbound [{}] started pointing to {}:{}", tag, remoteAddress, remotePort);
    }

    @Override
    public void close() {
        running = false;
        logger.info("VMess outbound [{}] closed", tag);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
