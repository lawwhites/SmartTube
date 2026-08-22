package com.liskovsoft.smartyoutubetv2.common.proxy.xray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ClashConfigParserTest {

    @Test
    public void parsesBuiltinSubscription() throws Exception {
        File asset = new File("src/main/assets/xray_builtin_sub.yaml");
        assertTrue("builtin subscription asset missing", asset.exists());

        String yaml = new String(Files.readAllBytes(asset.toPath()), "UTF-8");
        List<ProxyNode> nodes = ClashConfigParser.parse(yaml);

        assertTrue("expected many nodes, got " + nodes.size(), nodes.size() > 100);
        List<String> supported = java.util.Arrays.asList("vmess", "vless", "shadowsocks", "trojan");
        for (ProxyNode node : nodes) {
            assertFalse(node.name.isEmpty());
            assertTrue(node.port > 0);
            JSONObject outbound = node.getOutbound();
            assertEquals("proxy", outbound.getString("tag"));
            assertTrue("unexpected protocol: " + outbound.getString("protocol"),
                    supported.contains(outbound.getString("protocol")));
        }
    }

    @Test
    public void convertsSupportedProtocols() {
        String yaml = "proxies:\n"
                + "  - {name: v1, type: vless, server: a.com, port: 443, uuid: u-u-i-d, flow: xtls-rprx-vision, network: ws, tls: true, servername: sni.com, client-fingerprint: chrome, reality-opts: {public-key: pk, short-id: '01'}, ws-opts: {path: /ray, headers: {Host: h.com}}}\n"
                + "  - {name: t1, type: trojan, server: b.com, port: 443, password: pw, sni: b.com, network: grpc, grpc-opts: {grpc-service-name: svc}}\n"
                + "  - {name: s1, type: ss, server: c.com, port: 8388, cipher: aes-128-gcm, password: pw}\n"
                + "  - {name: v2, type: vmess, server: d.com, port: 9000, uuid: u-u-i-d, alterId: 0, cipher: auto}\n";

        List<ProxyNode> nodes = ClashConfigParser.parse(yaml);

        assertEquals(4, nodes.size());
        assertEquals("vless", nodes.get(0).type);
        assertTrue(nodes.get(0).getOutboundJson().contains("\"security\":\"reality\""));
        assertTrue(nodes.get(0).getOutboundJson().contains("\"wsSettings\""));
        assertTrue(nodes.get(1).getOutboundJson().contains("\"grpcSettings\""));
        // Trojan implies TLS even without an explicit "tls: true" key.
        assertTrue(nodes.get(1).getOutboundJson().contains("\"security\":\"tls\""));
        assertEquals("shadowsocks", nodes.get(2).getOutbound().optString("protocol"));
        assertTrue(nodes.get(3).getOutboundJson().contains("\"alterId\":0"));
    }

    @Test
    public void skipsUnsupportedAndMalformed() {
        String yaml = "proxies:\n"
                + "  - {name: h2, type: hysteria2, server: e.com, port: 443, password: pw}\n"
                + "  - {name: broken, type: vmess, server: '', port: 0}\n"
                + "  - {name: ok, type: ss, server: f.com, port: 8388, cipher: chacha20-ietf-poly1305, password: pw}\n";

        List<ProxyNode> nodes = ClashConfigParser.parse(yaml);

        assertEquals(1, nodes.size());
        assertEquals("ok", nodes.get(0).name);
    }
}
