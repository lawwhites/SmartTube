package com.liskovsoft.smartyoutubetv2.common.proxy.xray;

import android.util.Base64;

import com.liskovsoft.sharedutils.mylogger.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.net.URL;

/**
 * Resolves domain-based subscription nodes to currently-working IPs via the
 * DoH servers embedded in the Clash config (dns.proxy-server-nameserver).
 *
 * The service's DoH servers do health-based routing and return the IPs that
 * work *right now* — the raw domain (system DNS) and stale static IPs are
 * usually blocked. Each domain node is expanded into one variant per
 * candidate IP; Phase 1 TCP ping then prunes dead variants, and
 * {@link #dedupeByName} keeps the fastest variant per node.
 *
 * Wire format: RFC 8484, GET ?dns=&lt;base64url&gt;. The DoH endpoints are
 * IP-based with self-signed certs, hence the trust-all SSL context.
 */
public class DohResolver {
    private static final String TAG = DohResolver.class.getSimpleName();
    private static final int POOL_PASSES = 2;
    private static final int DOH_CONNECT_TIMEOUT_MS = 8_000;
    private static final int DOH_READ_TIMEOUT_MS = 10_000;
    private static final long RESOLVE_TOTAL_TIMEOUT_MS = 30_000;

    private static final SecureRandom RND = new SecureRandom();
    private static SSLSocketFactory sTrustAllFactory;
    private static HostnameVerifier sAllowAll;

    /**
     * Health-routing DoH endpoints of the bundled/default subscription
     * provider. Used only when the subscription omits its own
     * dns.proxy-server-nameserver section (newer exports do).
     */
    private static final List<String> FALLBACK_DOH_SERVERS = java.util.Arrays.asList(
            "https://20.247.42.211:36290/dns-query/clash?site=huojian",
            "https://20.239.241.138:55228/dns-query/clash?site=huojian",
            "https://104.208.122.199:62201/dns-query/clash?site=huojian");

    /** DoH server URLs from the config's dns.proxy-server-nameserver section. */
    public static List<String> extractDohServers(String yamlContent) {
        List<String> result = new ArrayList<>();
        try {
            Map<String, Object> root = new Yaml().load(yamlContent);
            Object dns = root != null ? root.get("dns") : null;
            Object servers = dns instanceof Map ? ((Map<?, ?>) dns).get("proxy-server-nameserver") : null;
            if (servers instanceof List) {
                for (Object item : (List<?>) servers) {
                    if (item != null && String.valueOf(item).startsWith("http")) {
                        result.add(String.valueOf(item));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract DoH servers: %s", e.getMessage());
        }
        return result;
    }

    public static boolean isIpv4(String server) {
        return server != null && server.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }

    /**
     * Expands domain-based nodes into one variant per DoH-resolved candidate
     * IP. Nodes that already use a plain IP are kept as-is. When DoH fails
     * for a domain, its nodes pass through unchanged (system-DNS fallback).
     */
    public static List<ProxyNode> expandWithResolvedIps(String yamlContent, List<ProxyNode> nodes) {
        Map<String, List<String>> pools = resolvePools(yamlContent, nodes);
        if (pools.isEmpty()) {
            return nodes;
        }

        List<ProxyNode> expanded = new ArrayList<>();
        for (ProxyNode node : nodes) {
            List<String> pool = pools.get(node.server);
            if (pool == null || pool.isEmpty()) {
                expanded.add(node);
                continue;
            }
            for (String ip : pool) {
                ProxyNode variant = node.withServer(ip);
                if (variant != null) {
                    expanded.add(variant);
                }
            }
        }
        Log.d(TAG, "DoH expansion: %d nodes -> %d variants", nodes.size(), expanded.size());
        return expanded;
    }

    /** Keeps the first (fastest, after sortByDelay) variant of each node name. */
    public static void dedupeByName(List<ProxyNode> nodes) {
        LinkedHashMap<String, ProxyNode> best = new LinkedHashMap<>();
        for (ProxyNode node : nodes) {
            best.putIfAbsent(node.name, node);
        }
        nodes.clear();
        nodes.addAll(best.values());
    }

    /** domain -> ordered candidate IP pool. Only for nodes whose server is a domain. */
    private static Map<String, List<String>> resolvePools(String yamlContent, List<ProxyNode> nodes) {
        Map<String, List<String>> pools = new LinkedHashMap<>();

        LinkedHashSet<String> domains = new LinkedHashSet<>();
        for (ProxyNode node : nodes) {
            if (!isIpv4(node.server)) {
                domains.add(node.server);
            }
        }
        if (domains.isEmpty()) {
            return pools;
        }

        List<String> extracted = extractDohServers(yamlContent);
        if (extracted.isEmpty()) {
            Log.d(TAG, "Subscription has no proxy-server-nameserver, using fallback DoH");
        }
        final List<String> dohServers = !extracted.isEmpty() ? extracted : FALLBACK_DOH_SERVERS;

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(domains.size(), 8));
        Map<String, Future<List<String>>> futures = new LinkedHashMap<>();
        for (String domain : domains) {
            futures.put(domain, pool.submit(() -> resolvePool(domain, dohServers)));
        }
        for (Map.Entry<String, Future<List<String>>> entry : futures.entrySet()) {
            try {
                List<String> ips = entry.getValue().get(RESOLVE_TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                pools.put(entry.getKey(), ips);
                Log.d(TAG, "DoH pool %s: %s", entry.getKey(), ips);
            } catch (Exception e) {
                Log.e(TAG, "DoH resolve failed for %s: %s", entry.getKey(), e.getMessage());
            }
        }
        pool.shutdownNow();
        return pools;
    }

    /** Queries every DoH server POOL_PASSES times, merging into an ordered pool. */
    private static List<String> resolvePool(String domain, List<String> dohServers) {
        LinkedHashSet<String> pool = new LinkedHashSet<>();
        for (int pass = 0; pass < POOL_PASSES; pass++) {
            for (String server : dohServers) {
                try {
                    pool.addAll(dohQuery(server, domain));
                } catch (Exception ignored) {
                    // try the next server
                }
            }
        }
        return new ArrayList<>(pool);
    }

    private static List<String> dohQuery(String server, String domain) throws Exception {
        String b64 = Base64.encodeToString(buildQuery(domain),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        URL url = new URL(server + (server.contains("?") ? "&" : "?") + "dns=" + b64);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(trustAllFactory());
        conn.setHostnameVerifier(allowAllVerifier());
        conn.setConnectTimeout(DOH_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(DOH_READ_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/dns-message");
        try {
            if (conn.getResponseCode() != 200) {
                return new ArrayList<>();
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[4_096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                body.write(buffer, 0, read);
            }
            in.close();
            return parseAnswers(body.toByteArray());
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] buildQuery(String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, RND.nextInt(0x10000));
        writeU16(out, 0x0100); // RD
        writeU16(out, 1); // QDCOUNT
        writeU16(out, 0);
        writeU16(out, 0);
        writeU16(out, 0);
        for (String label : name.split("\\.")) {
            out.write(label.length());
            byte[] bytes = label.getBytes();
            out.write(bytes, 0, bytes.length);
        }
        out.write(0);
        writeU16(out, 1); // QTYPE A
        writeU16(out, 1); // QCLASS IN
        return out.toByteArray();
    }

    private static List<String> parseAnswers(byte[] data) {
        List<String> ips = new ArrayList<>();
        if (data.length < 12) {
            return ips;
        }
        int questions = u16(data, 4);
        int answers = u16(data, 6);
        int off = 12;
        for (int i = 0; i < questions; i++) {
            off = skipName(data, off);
            off += 4;
        }
        for (int i = 0; i < answers; i++) {
            off = skipName(data, off);
            int type = u16(data, off);
            int rdlen = u16(data, off + 8);
            off += 10;
            if (type == 1 && rdlen == 4 && off + 4 <= data.length) {
                ips.add((data[off] & 0xff) + "." + (data[off + 1] & 0xff) + "."
                        + (data[off + 2] & 0xff) + "." + (data[off + 3] & 0xff));
            }
            off += rdlen;
        }
        return ips;
    }

    private static int skipName(byte[] data, int off) {
        while (off < data.length) {
            int b = data[off] & 0xff;
            if (b == 0) {
                return off + 1;
            }
            if ((b & 0xc0) == 0xc0) {
                return off + 2;
            }
            off += 1 + b;
        }
        return off;
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write(value >> 8);
        out.write(value & 0xff);
    }

    private static int u16(byte[] data, int off) {
        return ((data[off] & 0xff) << 8) | (data[off + 1] & 0xff);
    }

    private static synchronized SSLSocketFactory trustAllFactory() throws Exception {
        if (sTrustAllFactory == null) {
            TrustManager[] trustAll = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, RND);
            sTrustAllFactory = context.getSocketFactory();
        }
        return sTrustAllFactory;
    }

    private static synchronized HostnameVerifier allowAllVerifier() {
        if (sAllowAll == null) {
            sAllowAll = (hostname, session) -> true;
        }
        return sAllowAll;
    }
}
