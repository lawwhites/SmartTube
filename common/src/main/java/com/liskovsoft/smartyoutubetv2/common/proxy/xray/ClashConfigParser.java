package com.liskovsoft.smartyoutubetv2.common.proxy.xray;

import com.liskovsoft.sharedutils.mylogger.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses a Clash-format subscription YAML and converts supported proxies
 * (vmess / vless / ss / trojan) into Xray outbound JSON objects.
 * Unsupported or malformed nodes are skipped.
 */
public class ClashConfigParser {
    private static final String TAG = ClashConfigParser.class.getSimpleName();

    @SuppressWarnings("unchecked")
    public static List<ProxyNode> parse(String yamlContent) {
        List<ProxyNode> result = new ArrayList<>();

        Map<String, Object> root;
        try {
            root = new Yaml().load(yamlContent);
        } catch (Exception e) {
            Log.e(TAG, "Subscription is not a valid YAML: %s", e.getMessage());
            return result;
        }

        if (root == null || !(root.get("proxies") instanceof List)) {
            Log.e(TAG, "Subscription has no 'proxies' list");
            return result;
        }

        for (Object item : (List<Object>) root.get("proxies")) {
            if (!(item instanceof Map)) {
                continue;
            }
            try {
                ProxyNode node = convert((Map<String, Object>) item);
                if (node != null) {
                    result.add(node);
                }
            } catch (Exception e) {
                Log.e(TAG, "Skipping malformed node: %s", e.getMessage());
            }
        }

        return result;
    }

    private static ProxyNode convert(Map<String, Object> map) throws JSONException {
        String type = str(map, "type");
        String name = str(map, "name");
        String server = str(map, "server");
        int port = num(map, "port");

        if (name == null || server == null || port <= 0) {
            return null;
        }

        JSONObject outbound = new JSONObject();
        JSONObject streamSettings = buildStreamSettings(map, type);
        if (streamSettings.length() > 0) {
            outbound.put("streamSettings", streamSettings);
        }

        switch (type == null ? "" : type) {
            case "vmess":
                outbound.put("protocol", "vmess");
                outbound.put("settings", new JSONObject().put("vnext", new JSONArray().put(
                        new JSONObject()
                                .put("address", server)
                                .put("port", port)
                                .put("users", new JSONArray().put(new JSONObject()
                                        .put("id", str(map, "uuid"))
                                        .put("alterId", num(map, "alterId"))
                                        .put("security", str(map, "cipher", "auto")))))));
                break;
            case "vless": {
                JSONObject user = new JSONObject()
                        .put("id", str(map, "uuid"))
                        .put("encryption", "none");
                String flow = str(map, "flow");
                if (flow != null) {
                    user.put("flow", flow);
                }
                outbound.put("protocol", "vless");
                outbound.put("settings", new JSONObject().put("vnext", new JSONArray().put(
                        new JSONObject()
                                .put("address", server)
                                .put("port", port)
                                .put("users", new JSONArray().put(user)))));
                break;
            }
            case "ss":
                outbound.put("protocol", "shadowsocks");
                outbound.put("settings", new JSONObject().put("servers", new JSONArray().put(
                        new JSONObject()
                                .put("address", server)
                                .put("port", port)
                                .put("method", str(map, "cipher"))
                                .put("password", str(map, "password")))));
                break;
            case "trojan":
                outbound.put("protocol", "trojan");
                outbound.put("settings", new JSONObject().put("servers", new JSONArray().put(
                        new JSONObject()
                                .put("address", server)
                                .put("port", port)
                                .put("password", str(map, "password")))));
                break;
            default:
                Log.d(TAG, "Unsupported proxy type '%s', skipping node '%s'", type, name);
                return null;
        }

        outbound.put("tag", "proxy");
        return new ProxyNode(name, type, server, port, outbound);
    }

    private static JSONObject buildStreamSettings(Map<String, Object> map, String type) throws JSONException {
        JSONObject stream = new JSONObject();

        String network = str(map, "network", "tcp");
        stream.put("network", network);

        // Trojan is always TLS (Clash configs omit the explicit "tls: true" flag for it).
        boolean tls = bool(map, "tls") || "trojan".equals(type);
        boolean reality = map.get("reality-opts") instanceof Map;
        String serverName = firstNonNull(str(map, "servername"), str(map, "sni"), str(map, "host"));

        if (reality) {
            stream.put("security", "reality");
            @SuppressWarnings("unchecked")
            Map<String, Object> realityOpts = (Map<String, Object>) map.get("reality-opts");
            JSONObject realitySettings = new JSONObject();
            if (serverName != null) {
                realitySettings.put("serverName", serverName);
            }
            putIfNotNull(realitySettings, "publicKey", str(realityOpts, "public-key"));
            putIfNotNull(realitySettings, "shortId", str(realityOpts, "short-id"));
            realitySettings.put("fingerprint", str(map, "client-fingerprint", "chrome"));
            stream.put("realitySettings", realitySettings);
        } else if (tls) {
            stream.put("security", "tls");
            JSONObject tlsSettings = new JSONObject();
            if (serverName != null) {
                tlsSettings.put("serverName", serverName);
            }
            // NOTE: "allowInsecure" was removed in newer Xray-core (config load fails
            // whenever the key is present). Fronting nodes use valid certs anyway.
            stream.put("tlsSettings", tlsSettings);
        } else {
            stream.put("security", "none");
        }

        switch (network) {
            case "ws": {
                JSONObject wsSettings = new JSONObject();
                @SuppressWarnings("unchecked")
                Map<String, Object> wsOpts = map.get("ws-opts") instanceof Map
                        ? (Map<String, Object>) map.get("ws-opts") : null;
                String path = wsOpts != null ? str(wsOpts, "path", "/") : "/";
                wsSettings.put("path", path);
                if (wsOpts != null && wsOpts.get("headers") instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> headers = (Map<String, Object>) wsOpts.get("headers");
                    JSONObject headersJson = new JSONObject();
                    for (Map.Entry<String, Object> entry : headers.entrySet()) {
                        headersJson.put(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                    wsSettings.put("headers", headersJson);
                }
                stream.put("wsSettings", wsSettings);
                break;
            }
            case "grpc": {
                JSONObject grpcSettings = new JSONObject();
                @SuppressWarnings("unchecked")
                Map<String, Object> grpcOpts = map.get("grpc-opts") instanceof Map
                        ? (Map<String, Object>) map.get("grpc-opts") : null;
                grpcSettings.put("serviceName", grpcOpts != null ? str(grpcOpts, "grpc-service-name", "") : "");
                stream.put("grpcSettings", grpcSettings);
                break;
            }
            default:
                break;
        }

        return stream;
    }

    private static String str(Map<String, Object> map, String key) {
        return str(map, key, null);
    }

    private static String str(Map<String, Object> map, String key, String fallback) {
        Object value = map != null ? map.get(key) : null;
        return value != null ? String.valueOf(value) : fallback;
    }

    private static int num(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value != null ? Integer.parseInt(String.valueOf(value)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static void putIfNotNull(JSONObject json, String key, String value) throws JSONException {
        if (value != null) {
            json.put(key, value);
        }
    }
}
