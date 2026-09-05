package com.v2ray.proxy.vmess;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import org.bouncycastle.crypto.digests.SHAKEDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/**
 * Netty encoders and decoders for VMess AEAD chunk-stream framing.
 * Optimized for Android ART runtime:
 * - Reuses Cipher instances across chunk stream processing.
 * - Utilizes direct ByteBuffer zero-copy encryption/decryption without temporary byte[] allocations.
 */
public class VMessChunkCodec {

    public static final int MAX_CHUNK_SIZE = 16384; // 16KB max chunk payload

    /**
     * Encodes raw data stream into VMess AEAD chunks.
     */
    public static class ChunkEncoder extends MessageToByteEncoder<ByteBuf> {
        private final byte[] iv;
        private final boolean chunkMasking;
        private final SHAKEDigest shake;
        private final Cipher cipher;
        private final SecretKeySpec keySpec;
        private int count = 0;

        public ChunkEncoder(byte[] key, byte[] iv, boolean chunkMasking) {
            this.iv = iv;
            this.chunkMasking = chunkMasking;
            this.keySpec = new SecretKeySpec(key, "AES");
            try {
                this.cipher = Cipher.getInstance("AES/GCM/NoPadding");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (chunkMasking) {
                this.shake = new SHAKEDigest(128);
                this.shake.update(iv, 0, iv.length);
            } else {
                this.shake = null;
            }
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
            while (msg.isReadable()) {
                int chunkSize = Math.min(msg.readableBytes(), MAX_CHUNK_SIZE);
                byte[] nonce = generateChunkNonce(iv, count++);

                // Wire length includes the 16-byte GCM tag (Go: payloadSize + auth.Overhead()).
                int len = chunkSize + 16;
                if (chunkMasking) {
                    byte[] maskBytes = new byte[2];
                    shake.doOutput(maskBytes, 0, 2);
                    int mask = ((maskBytes[0] & 0xFF) << 8) | (maskBytes[1] & 0xFF);
                    len ^= mask;
                }

                out.writeShort(len);

                int writerIdx = out.writerIndex();
                out.ensureWritable(chunkSize + 16);
                ByteBuffer inNio = msg.nioBuffer(msg.readerIndex(), chunkSize);
                ByteBuffer outNio = out.nioBuffer(writerIdx, chunkSize + 16);

                cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, nonce));
                int produced = cipher.doFinal(inNio, outNio);
                out.writerIndex(writerIdx + produced);
                msg.skipBytes(chunkSize);
            }
        }
    }

    /**
     * Decodes VMess AEAD chunks into raw data stream.
     */
    public static class ChunkDecoder extends ByteToMessageDecoder {
        private final byte[] iv;
        private final boolean chunkMasking;
        private final SHAKEDigest shake;
        private final Cipher cipher;
        private final SecretKeySpec keySpec;
        private int count = 0;
        /** Mask bytes already drawn from the SHAKE stream for the in-progress chunk.
         *  SHAKE output cannot be rewound, so when a chunk arrives split across TCP
         *  segments we must cache the mask until the chunk completes. */
        private byte[] curMask;

        public ChunkDecoder(byte[] key, byte[] iv, boolean chunkMasking) {
            this.iv = iv;
            this.chunkMasking = chunkMasking;
            this.keySpec = new SecretKeySpec(key, "AES");
            try {
                this.cipher = Cipher.getInstance("AES/GCM/NoPadding");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (chunkMasking) {
                this.shake = new SHAKEDigest(128);
                this.shake.update(iv, 0, iv.length);
            } else {
                this.shake = null;
            }
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            while (in.readableBytes() >= 2) {
                in.markReaderIndex();
                int rawLen = in.readUnsignedShort();
                int len = rawLen;
                if (chunkMasking) {
                    if (curMask == null) {
                        curMask = new byte[2];
                        shake.doOutput(curMask, 0, 2);
                    }
                    int mask = ((curMask[0] & 0xFF) << 8) | (curMask[1] & 0xFF);
                    len ^= mask;
                }

                if (len == 16) {
                    // EOF chunk: Go seals an empty payload to signal end of stream
                    // (size == auth.Overhead()). Consume it and stop.
                    if (in.readableBytes() < 16) {
                        in.resetReaderIndex();
                        return;
                    }
                    in.skipBytes(16);
                    curMask = null;
                    return;
                }

                // Wire length already includes the 16-byte GCM tag.
                int totalEncryptedLen = len;
                if (in.readableBytes() < totalEncryptedLen) {
                    in.resetReaderIndex();
                    return;
                }
                curMask = null;

                byte[] nonce = generateChunkNonce(iv, count++);
                cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, nonce));

                ByteBuf decBuf = ctx.alloc().buffer(len - 16);
                ByteBuffer inNio = in.nioBuffer(in.readerIndex(), totalEncryptedLen);
                ByteBuffer outNio = decBuf.nioBuffer(0, len - 16);
                try {
                    int decrypted = cipher.doFinal(inNio, outNio);
                    decBuf.writerIndex(decrypted);
                } catch (Exception e) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = in.readerIndex(); i < Math.min(in.readerIndex() + 16, in.writerIndex()); i++) {
                        sb.append(String.format("%02x ", in.getByte(i)));
                    }
                    System.err.println("[ChunkDecoder] decrypt fail len=" + len + " count=" + (count - 1)
                            + " masking=" + chunkMasking + " first16=" + sb);
                    decBuf.release();
                    throw e;
                }
                in.skipBytes(totalEncryptedLen);

                out.add(decBuf);
            }
        }
    }

    public static byte[] generateChunkNonce(byte[] iv, int count) {
        byte[] nonce = Arrays.copyOf(iv, 12);
        // Go: binary.BigEndian.PutUint16(nonce, count) — counter in the FIRST two bytes.
        nonce[0] = (byte) ((count >> 8) & 0xFF);
        nonce[1] = (byte) (count & 0xFF);
        return nonce;
    }
}
