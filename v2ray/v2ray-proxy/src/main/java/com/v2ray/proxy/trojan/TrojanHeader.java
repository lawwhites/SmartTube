package com.v2ray.proxy.trojan;

import com.v2ray.common.net.Destination;
import com.v2ray.common.net.Network;
import io.netty.buffer.ByteBuf;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Trojan protocol header encoding and decoding.
 * Conforms to the Trojan protocol standard used in V2Ray.
 */
public class TrojanHeader {
    public static final byte COMMAND_TCP = 0x01;
    public static final byte COMMAND_UDP = 0x03;

    public static final byte ADDR_TYPE_IPV4 = 0x01;
    public static final byte ADDR_TYPE_DOMAIN = 0x03;
    public static final byte ADDR_TYPE_IPV6 = 0x04;

    private static final byte[] CRLF = new byte[]{'\r', '\n'};

    private String hexPasswordHash;
    private byte command = COMMAND_TCP;
    private Destination destination;

    public TrojanHeader() {}

    public TrojanHeader(String hexPasswordHash, byte command, Destination destination) {
        this.hexPasswordHash = hexPasswordHash;
        this.command = command;
        this.destination = destination;
    }

    public static String computePasswordHash(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-224");
            byte[] digest = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-224 not available", e);
        }
    }

    public void encode(ByteBuf out) {
        // 1. 56 bytes hex password hash
        out.writeBytes(hexPasswordHash.getBytes(StandardCharsets.US_ASCII));
        // 2. CRLF
        out.writeBytes(CRLF);
        // 3. Command
        out.writeByte(command);
        // 4. Address & Port
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
        out.writeShort(destination.getPort());
        // 5. CRLF
        out.writeBytes(CRLF);
    }

    private static void writeDomain(ByteBuf out, String host) {
        byte[] bytes = host.getBytes(StandardCharsets.US_ASCII);
        out.writeByte(ADDR_TYPE_DOMAIN);
        out.writeByte(bytes.length);
        out.writeBytes(bytes);
    }

    public static TrojanHeader decode(ByteBuf in) {
        if (in.readableBytes() < 62) { // 56 (hash) + 2 (crlf) + 1 (cmd) + 1 (addrType) + 2 (port) = 62 min
            return null;
        }

        int readerIndex = in.readerIndex();

        byte[] hashBytes = new byte[56];
        in.readBytes(hashBytes);
        String hash = new String(hashBytes, StandardCharsets.US_ASCII);

        byte cr1 = in.readByte();
        byte lf1 = in.readByte();
        if (cr1 != '\r' || lf1 != '\n') {
            in.readerIndex(readerIndex);
            return null;
        }

        byte cmd = in.readByte();
        byte addrType = in.readByte();

        String host;
        if (addrType == ADDR_TYPE_IPV4) {
            if (in.readableBytes() < 6) { // 4 bytes IP + 2 bytes port
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
            int len = in.readUnsignedByte();
            if (in.readableBytes() < len + 2) {
                in.readerIndex(readerIndex);
                return null;
            }
            byte[] domain = new byte[len];
            in.readBytes(domain);
            host = new String(domain, StandardCharsets.US_ASCII);
        } else if (addrType == ADDR_TYPE_IPV6) {
            if (in.readableBytes() < 18) { // 16 bytes IP + 2 bytes port
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
            in.readerIndex(readerIndex);
            throw new IllegalArgumentException("Unknown Trojan address type: " + addrType);
        }

        int port = in.readUnsignedShort();

        if (in.readableBytes() < 2) {
            in.readerIndex(readerIndex);
            return null;
        }
        byte cr2 = in.readByte();
        byte lf2 = in.readByte();
        if (cr2 != '\r' || lf2 != '\n') {
            in.readerIndex(readerIndex);
            return null;
        }

        Network net = (cmd == COMMAND_UDP) ? Network.UDP : Network.TCP;
        Destination dest = new Destination(net, host, port);

        return new TrojanHeader(hash, cmd, dest);
    }

    public String getHexPasswordHash() {
        return hexPasswordHash;
    }

    public byte getCommand() {
        return command;
    }

    public Destination getDestination() {
        return destination;
    }
}
