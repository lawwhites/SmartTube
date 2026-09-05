package com.v2ray.proxy.vmess;

import com.v2ray.common.net.Destination;
import com.v2ray.common.net.Network;
import com.v2ray.proxy.vmess.aead.VMessAead;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.NetUtil;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/**
 * VMess request and response header encoder/decoder.
 */
public class VMessHeader {

    public static final byte VERSION = 0x01;

    public static final byte OPTION_CHUNK_STREAM = 0x01;
    public static final byte OPTION_CHUNK_MASKING = 0x04;
    public static final byte OPTION_GLOBAL_PADDING = 0x08;
    public static final byte OPTION_AUTHENTICATED_LENGTH = 0x10;

    public static final byte SECURITY_NONE = 0x00;
    public static final byte SECURITY_LEGACY = 0x01;
    public static final byte SECURITY_AUTO = 0x02;
    public static final byte SECURITY_AES_128_GCM = 0x03;
    public static final byte SECURITY_CHACHA20_POLY1305 = 0x04;

    public static final byte ADDR_TYPE_IPV4 = 0x01;
    public static final byte ADDR_TYPE_DOMAIN = 0x02;
    public static final byte ADDR_TYPE_IPV6 = 0x03;

    public static final byte CMD_TCP = 0x01;
    public static final byte CMD_UDP = 0x02;

    private static final SecureRandom RANDOM = new SecureRandom();

    public static class RequestHeader {
        public byte version = VERSION;
        public byte[] requestBodyIV = new byte[16];
        public byte[] requestBodyKey = new byte[16];
        public byte responseHeader;
        public byte option = OPTION_CHUNK_STREAM | OPTION_CHUNK_MASKING;
        public byte security = SECURITY_AES_128_GCM;
        public byte command = CMD_TCP;
        public Destination destination;
        public UUID userUuid;

        public byte[] responseBodyKey() {
            try {
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                return Arrays.copyOf(sha256.digest(requestBodyKey), 16);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public byte[] responseBodyIV() {
            try {
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                return Arrays.copyOf(sha256.digest(requestBodyIV), 16);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static byte[] encodeRequestPayload(RequestHeader header) {
        ByteBuf buf = Unpooled.buffer(128);
        try {
            buf.writeByte(header.version);
            buf.writeBytes(header.requestBodyIV);
            buf.writeBytes(header.requestBodyKey);
            buf.writeByte(header.responseHeader);
            buf.writeByte(header.option);

            int paddingLen = RANDOM.nextInt(16);
            byte secByte = (byte) ((paddingLen << 4) | (header.security & 0x0F));
            buf.writeByte(secByte);
            buf.writeByte(0); // reserved
            buf.writeByte(header.command);

            Destination dest = header.destination;
            buf.writeShort(dest.getPort());

            String host = dest.getAddress();
            if (NetUtil.isValidIpV4Address(host)) {
                buf.writeByte(ADDR_TYPE_IPV4);
                buf.writeBytes(InetAddress.getByName(host).getAddress());
            } else if (NetUtil.isValidIpV6Address(host)) {
                buf.writeByte(ADDR_TYPE_IPV6);
                buf.writeBytes(InetAddress.getByName(host).getAddress());
            } else {
                buf.writeByte(ADDR_TYPE_DOMAIN);
                byte[] domainBytes = host.getBytes(StandardCharsets.UTF_8);
                buf.writeByte(domainBytes.length);
                buf.writeBytes(domainBytes);
            }

            if (paddingLen > 0) {
                byte[] pad = new byte[paddingLen];
                RANDOM.nextBytes(pad);
                buf.writeBytes(pad);
            }

            // FNV1a-32 checksum of all preceding bytes
            byte[] headerBytes = new byte[buf.readableBytes()];
            buf.getBytes(0, headerBytes);
            int fnv = fnv1a32(headerBytes);
            buf.writeInt(fnv);

            byte[] fullPayload = new byte[buf.readableBytes()];
            buf.readBytes(fullPayload);
            return fullPayload;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            buf.release();
        }
    }

    public static RequestHeader decodeRequestPayload(byte[] payload) {
        ByteBuf buf = Unpooled.wrappedBuffer(payload);
        try {
            int preChecksumLen = payload.length - 4;
            byte[] preChecksumBytes = Arrays.copyOf(payload, preChecksumLen);
            int expectedChecksum = ByteBuffer.wrap(payload, preChecksumLen, 4).getInt();
            int actualChecksum = fnv1a32(preChecksumBytes);
            if (expectedChecksum != actualChecksum) {
                throw new IllegalArgumentException("VMess request header FNV1a checksum mismatch");
            }

            RequestHeader header = new RequestHeader();
            header.version = buf.readByte();
            if (header.version != VERSION) {
                throw new IllegalArgumentException("Unsupported VMess version: " + header.version);
            }

            buf.readBytes(header.requestBodyIV);
            buf.readBytes(header.requestBodyKey);
            header.responseHeader = buf.readByte();
            header.option = buf.readByte();

            byte secByte = buf.readByte();
            int paddingLen = (secByte >> 4) & 0x0F;
            header.security = (byte) (secByte & 0x0F);

            buf.readByte(); // reserved
            header.command = buf.readByte();

            int port = buf.readUnsignedShort();
            byte addrType = buf.readByte();
            String host;
            if (addrType == ADDR_TYPE_IPV4) {
                byte[] ip = new byte[4];
                buf.readBytes(ip);
                host = InetAddress.getByAddress(ip).getHostAddress();
            } else if (addrType == ADDR_TYPE_DOMAIN) {
                int domainLen = buf.readUnsignedByte();
                byte[] domainBytes = new byte[domainLen];
                buf.readBytes(domainBytes);
                host = new String(domainBytes, StandardCharsets.UTF_8);
            } else if (addrType == ADDR_TYPE_IPV6) {
                byte[] ip = new byte[16];
                buf.readBytes(ip);
                host = InetAddress.getByAddress(ip).getHostAddress();
            } else {
                throw new IllegalArgumentException("Unknown address type: " + addrType);
            }

            header.destination = new Destination(header.command == CMD_UDP ? Network.UDP : Network.TCP, host, port);

            if (paddingLen > 0) {
                buf.skipBytes(paddingLen);
            }

            return header;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            buf.release();
        }
    }

    public static int fnv1a32(byte[] data) {
        int hash = 0x811c9dc5;
        for (byte b : data) {
            hash ^= (b & 0xFF);
            hash *= 0x01000193;
        }
        return hash;
    }
}
