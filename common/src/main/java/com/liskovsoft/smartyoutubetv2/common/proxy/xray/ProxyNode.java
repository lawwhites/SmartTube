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

    /**
     * Returns a copy of this node with the outbound server address replaced
     * (used by DohResolver to expand a domain node into per-IP variants).
     */
    public ProxyNode withServer(String newServer) {
        try {
            JSONObject outbound = new JSONObject(getOutboundJson());
            JSONObject settings = outbound.optJSONObject("settings");
            if (settings != null) {
                org.json.JSONArray vnext = settings.optJSONArray("vnext");
                if (vnext != null && vnext.length() > 0) {
                    vnext.getJSONObject(0).put("address", newServer);
                }
                org.json.JSONArray servers = settings.optJSONArray("servers");
                if (servers != null && servers.length() > 0) {
                    servers.getJSONObject(0).put("address", newServer);
                }
            }
            return new ProxyNode(name, type, newServer, port, outbound);
        } catch (org.json.JSONException e) {
            return null;
        }
    }
}
