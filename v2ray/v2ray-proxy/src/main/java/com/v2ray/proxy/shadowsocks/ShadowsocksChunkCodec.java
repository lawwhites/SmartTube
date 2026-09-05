package com.v2ray.proxy.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Netty encoder and decoder for Shadowsocks SIP008 AEAD chunks.
 * Highly optimized for mobile (Android ART):
 * - Reuses Cipher instances per channel session (no GC allocations per chunk).
 * - Leverages direct ByteBuf and Java NIO ByteBuffer zero-copy encryption/decryption.
 */
public class ShadowsocksChunkCodec {

    public static final int MAX_CHUNK_PAYLOAD = 16383; // 0x3FFF

    public static class Encoder extends MessageToByteEncoder<ByteBuf> {
        private final String method;
        private final Cipher cipher;
        private final SecretKeySpec keySpec;
        private long counter = 0;

        public Encoder(String method, byte[] subkey) {
            this.method = method;
            this.cipher = ShadowsocksCrypto.createCipher(method);
            this.keySpec = ShadowsocksCrypto.createKeySpec(method, subkey);
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
            while (msg.isReadable()) {
                int chunkSize = Math.min(msg.readableBytes(), MAX_CHUNK_PAYLOAD);

                // 1. Encrypt 2-byte chunk length
                byte[] lenPlain = new byte[]{(byte) ((chunkSize >> 8) & 0xFF), (byte) (chunkSize & 0xFF)};
                byte[] lenNonce = ShadowsocksCrypto.generateNonce(counter++);
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, ShadowsocksCrypto.createParamSpec(method, lenNonce));

                int lenWriterIdx = out.writerIndex();
                out.ensureWritable(18 + chunkSize + 16);
                ByteBuffer lenOut = out.nioBuffer(lenWriterIdx, 18);
                int lenEncProduced = cipher.doFinal(ByteBuffer.wrap(lenPlain), lenOut);
                out.writerIndex(lenWriterIdx + lenEncProduced);

                // 2. Encrypt chunk payload directly from msg to out (zero heap array copy)
                byte[] payloadNonce = ShadowsocksCrypto.generateNonce(counter++);
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, ShadowsocksCrypto.createParamSpec(method, payloadNonce));

                int payloadWriterIdx = out.writerIndex();
                ByteBuffer inNio = msg.nioBuffer(msg.readerIndex(), chunkSize);
                ByteBuffer payloadOut = out.nioBuffer(payloadWriterIdx, chunkSize + 16);
                int payloadProduced = cipher.doFinal(inNio, payloadOut);
                out.writerIndex(payloadWriterIdx + payloadProduced);

                msg.skipBytes(chunkSize);
            }
        }
    }

    public static class Decoder extends ByteToMessageDecoder {
        private final String method;
        private final Cipher cipher;
        private final SecretKeySpec keySpec;
        private long counter = 0;
        private int currentPayloadLen = -1;

        public Decoder(String method, byte[] subkey) {
            this.method = method;
            this.cipher = ShadowsocksCrypto.createCipher(method);
            this.keySpec = ShadowsocksCrypto.createKeySpec(method, subkey);
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            while (true) {
                if (currentPayloadLen == -1) {
                    if (in.readableBytes() < 18) {
                        return; // Need 2 bytes + 16 bytes tag
                    }
                    byte[] lenNonce = ShadowsocksCrypto.generateNonce(counter++);
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, ShadowsocksCrypto.createParamSpec(method, lenNonce));
                    ByteBuffer inLen = in.nioBuffer(in.readerIndex(), 18);
                    byte[] lenPlain = new byte[2];
                    cipher.doFinal(inLen, ByteBuffer.wrap(lenPlain));
                    in.skipBytes(18);
                    currentPayloadLen = ((lenPlain[0] & 0xFF) << 8) | (lenPlain[1] & 0xFF);
                }

                int totalEncrypted = currentPayloadLen + 16;
                if (in.readableBytes() < totalEncrypted) {
                    return;
                }

                byte[] payloadNonce = ShadowsocksCrypto.generateNonce(counter++);
                cipher.init(Cipher.DECRYPT_MODE, keySpec, ShadowsocksCrypto.createParamSpec(method, payloadNonce));

                ByteBuf decBuf = ctx.alloc().buffer(currentPayloadLen);
                ByteBuffer inNio = in.nioBuffer(in.readerIndex(), totalEncrypted);
                ByteBuffer outNio = decBuf.nioBuffer(0, currentPayloadLen);
                int decrypted = cipher.doFinal(inNio, outNio);
                decBuf.writerIndex(decrypted);
                in.skipBytes(totalEncrypted);

                currentPayloadLen = -1;
                out.add(decBuf);
            }
        }
    }
}
