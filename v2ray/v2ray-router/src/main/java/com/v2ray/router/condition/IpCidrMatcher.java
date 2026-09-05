package com.v2ray.router.condition;

import java.net.InetAddress;

/**
 * High performance CIDR subnet matcher for IPv4 and IPv6.
 */
public class IpCidrMatcher {
    private final byte[] networkBytes;
    private final int prefixLength;

    public IpCidrMatcher(String cidr) {
        String[] parts = cidr.trim().split("/");
        try {
            InetAddress addr = InetAddress.getByName(parts[0]);
            this.networkBytes = addr.getAddress();
            if (parts.length > 1) {
                this.prefixLength = Integer.parseInt(parts[1]);
            } else {
                this.prefixLength = networkBytes.length * 8;
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid CIDR notation: " + cidr, e);
        }
    }

    public boolean matches(String ipStr) {
        try {
            InetAddress addr = InetAddress.getByName(ipStr);
            byte[] ipBytes = addr.getAddress();

            if (ipBytes.length != networkBytes.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (ipBytes[i] != networkBytes[i]) {
                    return false;
                }
            }

            int remainingBits = prefixLength % 8;
            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                return (ipBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
