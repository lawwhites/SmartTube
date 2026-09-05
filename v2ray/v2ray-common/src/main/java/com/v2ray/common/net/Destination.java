package com.v2ray.common.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Destination represents a network destination, consisting of network type (TCP/UDP),
 * host address (domain or IP), and port. Equivalent to Go's common/net.Destination.
 */
public final class Destination {
    private final Network network;
    private final String address;
    private final int port;

    public Destination(Network network, String address, int port) {
        if (network == null) {
            throw new IllegalArgumentException("Network cannot be null");
        }
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Address cannot be null or empty");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        this.network = network;
        this.address = address;
        this.port = port;
    }

    public static Destination tcp(String address, int port) {
        return new Destination(Network.TCP, address, port);
    }

    public static Destination udp(String address, int port) {
        return new Destination(Network.UDP, address, port);
    }

    public static Destination fromSocketAddress(Network network, InetSocketAddress socketAddress) {
        return new Destination(network, socketAddress.getHostString(), socketAddress.getPort());
    }

    public Network getNetwork() {
        return network;
    }

    public String getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public boolean isTcp() {
        return network == Network.TCP;
    }

    public boolean isUdp() {
        return network == Network.UDP;
    }

    public InetSocketAddress toInetSocketAddress() {
        return new InetSocketAddress(address, port);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Destination that = (Destination) o;
        return port == that.port && network == that.network && Objects.equals(address, that.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(network, address, port);
    }

    @Override
    public String toString() {
        return network.name().toLowerCase() + ":" + address + ":" + port;
    }
}
