package com.v2ray.proxy.vless;

import com.v2ray.common.net.Destination;
import com.v2ray.common.net.Network;
import io.netty.buffer.ByteBuf;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * VLESS protocol header encoding and decoding.
 * Strictly conforms to V2Ray VLESS specification.
 */
public class VlessHeader {
    public static final byte VERSION = 0x00;
    public static final byte COMMAND_TCP = 0x01;
    public static final byte COMMAND_UDP = 0x02;
    public static final byte COMMAND_MUX = 0x03;

    public static final byte ADDR_TYPE_IPV4 = 0x01;
    public static final byte ADDR_TYPE_DOMAIN = 0x02;
    public static final byte ADDR_TYPE_IPV6 = 0x03;

    private byte version = VERSION;
    private UUID uuid;
    private byte command = COMMAND_TCP;
    private Destination destination;

    public VlessHeader() {}

    public VlessHeader(UUID uuid, byte command, Destination destination) {
        this.uuid = uuid;
        this.command = command;
        this.destination = destination;
    }

    public static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    public static UUID bytesToUuid(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        long high = bb.getLong();
        long low = bb.getLong();
        return new UUID(high, low);
    }

    /**
     * Encodes a VLESS request header into the provided ByteBuf.
     */
    public void encodeRequest(ByteBuf out) {
        // 1. Version (1 byte)
        out.writeByte(version);

        // 2. UUID (16 bytes)
        out.writeBytes(uuidToBytes(uuid));

        // 3. Addon length (1 byte) - 0 for standard VLESS
        out.writeByte(0x00);

        // 4. Command (1 byte)
        out.writeByte(command);

        // 5. Port (2 bytes, big-endian)
        out.writeShort(destination.getPort());

        // 6. Address Type and Address
        String host = destination.getAddress();
        if (io.netty.util.NetUtil.isValidIpV4Address(host)) {
            out.writeByte(ADDR_TYPE_IPV4);
            out.writeBytes(io.netty.util.NetUtil.createByteArrayFromIpAddressString(host));
        } else if (io.netty.util.NetUtil.isValidIpV6Address(host)) {
            out.writeByte(ADDR_TYPE_IPV6);
            out.writeBytes(io.netty.util.NetUtil.createByteArrayFromIpAddressString(host));
        } else {
            writeDomain(out, host);
        }
    }

    private static void writeDomain(ByteBuf out, String domain) {
        byte[] domainBytes = domain.getBytes(StandardCharsets.US_ASCII);
        out.writeByte(ADDR_TYPE_DOMAIN);
        out.writeByte(domainBytes.length);
        out.writeBytes(domainBytes);
    }

    /**
     * Decodes a VLESS request header from the ByteBuf.
     * Returns null if not enough bytes are readable.
     */
    public static VlessHeader decodeRequest(ByteBuf in) {
        if (in.readableBytes() < 22) { // 1(ver) + 16(uuid) + 1(addon) + 1(cmd) + 2(port) + 1(addrType) = 22 minimum
            return null;
        }

        int readerIndex = in.readerIndex();

        byte ver = in.readByte();
        if (ver != VERSION) {
            throw new IllegalArgumentException("Unsupported VLESS version: " + ver);
        }

        byte[] uuidBytes = new byte[16];
        in.readBytes(uuidBytes);
        UUID uuid = bytesToUuid(uuidBytes);

        int addonLen = in.readUnsignedByte();
        if (in.readableBytes() < addonLen) {
            in.readerIndex(readerIndex);
            return null;
        }
        in.skipBytes(addonLen); // Skip addons for now

        if (in.readableBytes() < 4) { // cmd(1) + port(2) + addrType(1)
            in.readerIndex(readerIndex);
            return null;
        }

        byte cmd = in.readByte();
        int port = in.readUnsignedShort();
        byte addrType = in.readByte();

        String host;
        if (addrType == ADDR_TYPE_IPV4) {
            if (in.readableBytes() < 4) {
                in.readerIndex(readerIndex);
                return null;
            }
            byte[] ip = new byte[4];
            in.readBytes(ip);
            host = (ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "." + (ip[2] & 0xFF) + "." + (ip[3] & 0xFF);
        } else if (addrType == ADDR_TYPE_DOMAIN) {
            if (in.readableBytes() < 1) {
                in.readerIndex(readerIndex);
                return null;
            }
            int domainLen = in.readUnsignedByte();
            if (in.readableBytes() < domainLen) {
                in.readerIndex(readerIndex);
                return null;
            }
            byte[] domainBytes = new byte[domainLen];
            in.readBytes(domainBytes);
            host = new String(domainBytes, StandardCharsets.US_ASCII);
        } else if (addrType == ADDR_TYPE_IPV6) {
            if (in.readableBytes() < 16) {
                in.readerIndex(readerIndex);
                return null;
            }
            byte[] ip6 = new byte[16];
            in.readBytes(ip6);
            try {
                host = InetAddress.getByAddress(ip6).getHostAddress();
            } catch (Exception e) {
                host = "::1";
            }
        } else {
            throw new IllegalArgumentException("Unknown VLESS address type: " + addrType);
        }

        Network net = (cmd == COMMAND_UDP) ? Network.UDP : Network.TCP;
        Destination dest = new Destination(net, host, port);

        VlessHeader header = new VlessHeader(uuid, cmd, dest);
        header.version = ver;
        return header;
    }

    /**
     * Encodes a standard VLESS response header (2 bytes: version 0x00 + addon length 0x00).
     */
    public static void encodeResponse(ByteBuf out) {
        out.writeByte(VERSION);
        out.writeByte(0x00);
    }

    public UUID getUuid() {
        return uuid;
    }

    public byte getCommand() {
        return command;
    }

    public Destination getDestination() {
        return destination;
    }
}
