package com.v2ray.config;

import com.v2ray.config.model.V2RayConfig;
import com.v2ray.core.instance.V2RayInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigLoaderTest {

    @Test
    public void testLoadConfigAndCreateInstance() throws Exception {
        String json = "{\n" +
                "  \"inbounds\": [\n" +
                "    {\n" +
                "      \"tag\": \"socks-in\",\n" +
                "      \"port\": 10808,\n" +
                "      \"protocol\": \"socks\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"outbounds\": [\n" +
                "    {\n" +
                "      \"tag\": \"direct\",\n" +
                "      \"protocol\": \"freedom\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"tag\": \"blocked\",\n" +
                "      \"protocol\": \"blackhole\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"routing\": {\n" +
                "    \"rules\": [\n" +
                "      {\n" +
                "        \"outboundTag\": \"blocked\",\n" +
                "        \"domain\": [\"domain:ads.com\"]\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}";

        V2RayConfig config = ConfigLoader.load(json);
        assertNotNull(config);
        assertEquals(1, config.getInbounds().size());
        assertEquals(2, config.getOutbounds().size());
        assertNotNull(config.getRouting());
        assertEquals(1, config.getRouting().getRules().size());

        V2RayInstance instance = ConfigLoader.createInstance(config);
        assertNotNull(instance);
        assertNotNull(instance.getInboundManager().getHandler("socks-in"));
        assertNotNull(instance.getOutboundManager().getHandler("direct"));
        assertNotNull(instance.getOutboundManager().getHandler("blocked"));
    }

    @Test
    public void testAllProtocolsConfigLoading() throws Exception {
        String json = "{\n" +
                "  \"inbounds\": [\n" +
                "    {\n" +
                "      \"tag\": \"vmess-in\",\n" +
                "      \"port\": 10001,\n" +
                "      \"protocol\": \"vmess\",\n" +
                "      \"settings\": {\n" +
                "        \"clients\": [{\"id\": \"6a2e4822-124b-47b2-bb0e-17cf3a8c3d9a\"}]\n" +
                "      }\n" +
                "    },\n" +
                "    {\n" +
                "      \"tag\": \"ss-in\",\n" +
                "      \"port\": 10002,\n" +
                "      \"protocol\": \"shadowsocks\",\n" +
                "      \"settings\": {\n" +
                "        \"method\": \"aes-128-gcm\",\n" +
                "        \"password\": \"secret123\"\n" +
                "      }\n" +
                "    },\n" +
                "    {\n" +
                "      \"tag\": \"doko-in\",\n" +
                "      \"port\": 10003,\n" +
                "      \"protocol\": \"dokodemo-door\",\n" +
                "      \"settings\": {\n" +
                "        \"address\": \"8.8.8.8\",\n" +
                "        \"port\": 53\n" +
                "      }\n" +
                "    }\n" +
                "  ],\n" +
                "  \"outbounds\": [\n" +
                "    {\n" +
                "      \"tag\": \"vmess-out\",\n" +
                "      \"protocol\": \"vmess\",\n" +
                "      \"settings\": {\n" +
                "        \"vnext\": [{\n" +
                "          \"address\": \"remote.vmess.com\",\n" +
                "          \"port\": 443,\n" +
                "          \"users\": [{\"id\": \"6a2e4822-124b-47b2-bb0e-17cf3a8c3d9a\"}]\n" +
                "        }]\n" +
                "      }\n" +
                "    },\n" +
                "    {\n" +
                "      \"tag\": \"ss-out\",\n" +
                "      \"protocol\": \"shadowsocks\",\n" +
                "      \"settings\": {\n" +
                "        \"servers\": [{\n" +
                "          \"address\": \"remote.ss.com\",\n" +
                "          \"port\": 8388,\n" +
                "          \"method\": \"aes-256-gcm\",\n" +
                "          \"password\": \"pass456\"\n" +
                "        }]\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        V2RayConfig config = ConfigLoader.load(json);
        assertNotNull(config);
        assertEquals(3, config.getInbounds().size());
        assertEquals(2, config.getOutbounds().size());

        V2RayInstance instance = ConfigLoader.createInstance(config);
        assertNotNull(instance.getInboundManager().getHandler("vmess-in"));
        assertNotNull(instance.getInboundManager().getHandler("ss-in"));
        assertNotNull(instance.getInboundManager().getHandler("doko-in"));
        assertNotNull(instance.getOutboundManager().getHandler("vmess-out"));
        assertNotNull(instance.getOutboundManager().getHandler("ss-out"));
    }
}
