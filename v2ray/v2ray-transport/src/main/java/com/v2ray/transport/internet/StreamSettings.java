package com.v2ray.transport.internet;

import com.v2ray.common.net.Network;

public class StreamSettings {
    private Network network = Network.TCP;
    private String security = "none";

    public StreamSettings() {}

    public StreamSettings(Network network, String security) {
        this.network = network;
        this.security = security;
    }

    public Network getNetwork() {
        return network;
    }

    public void setNetwork(Network network) {
        this.network = network;
    }

    public String getSecurity() {
        return security;
    }

    public void setSecurity(String security) {
        this.security = security;
    }
}
