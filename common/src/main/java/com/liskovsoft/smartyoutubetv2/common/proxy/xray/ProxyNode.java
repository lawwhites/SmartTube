package com.liskovsoft.smartyoutubetv2.common.proxy.xray;

import org.json.JSONObject;

/**
 * A single proxy server parsed from a Clash subscription,
 * already converted to an Xray outbound JSON object.
 */
public class ProxyNode {
    public final String name;
    public final String type;
    public final String server;
    public final int port;
    private final JSONObject mOutbound;
    /** Measured real delay in ms, -1 if unreachable, 0 if not tested yet. */
    public long delayMs;

    public ProxyNode(String name, String type, String server, int port, JSONObject outbound) {
        this.name = name;
        this.type = type;
        this.server = server;
        this.port = port;
        mOutbound = outbound;
    }

    public JSONObject getOutbound() {
        return mOutbound;
    }

    public String getOutboundJson() {
        return mOutbound.toString();
    }
}
