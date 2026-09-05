package com.v2ray.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.v2ray.config.model.*;
import com.v2ray.core.instance.V2RayInstance;
import com.v2ray.proxy.blackhole.BlackholeOutboundHandler;
import com.v2ray.proxy.freedom.FreedomOutboundHandler;
import com.v2ray.proxy.http.HttpProxyInboundHandler;
import com.v2ray.proxy.socks.Socks5InboundHandler;
import com.v2ray.proxy.trojan.TrojanInboundHandler;
import com.v2ray.proxy.trojan.TrojanOutboundHandler;
import com.v2ray.proxy.vless.VlessInboundHandler;
import com.v2ray.proxy.vless.VlessOutboundHandler;
import com.v2ray.router.DefaultRouter;
import com.v2ray.router.RoutingRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.util.UUID;

public class ConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public static V2RayConfig load(File file) throws Exception {
        return mapper.readValue(file, V2RayConfig.class);
    }

    public static V2RayConfig load(InputStream is) throws Exception {
        return mapper.readValue(is, V2RayConfig.class);
    }

    public static V2RayConfig load(String json) throws Exception {
        return mapper.readValue(json, V2RayConfig.class);
    }

    public static V2RayInstance createInstance(V2RayConfig config) {
        V2RayInstance instance = new V2RayInstance();

        // 1. Configure Outbounds
        if (config.getOutbounds() != null && !config.getOutbounds().isEmpty()) {
            String firstTag = config.getOutbounds().get(0).getTag();
            if (firstTag != null && instance.getRouter() instanceof DefaultRouter) {
                ((DefaultRouter) instance.getRouter()).setDefaultOutboundTag(firstTag);
            }
            for (OutboundConfig oc : config.getOutbounds()) {
                String protocol = oc.getProtocol() == null ? "freedom" : oc.getProtocol().toLowerCase();
                String tag = oc.getTag() == null ? protocol : oc.getTag();
                JsonNode settings = oc.getSettings();

                switch (protocol) {
                    case "freedom":
                        instance.getOutboundManager().addHandler(new FreedomOutboundHandler(tag));
                        break;
                    case "blackhole":
                        instance.getOutboundManager().addHandler(new BlackholeOutboundHandler(tag));
                        break;
                    case "vless":
                        if (settings != null && settings.has("vnext") && settings.get("vnext").isArray()) {
                            JsonNode vnext = settings.get("vnext").get(0);
                            String addr = vnext.get("address").asText();
                            int port = vnext.get("port").asInt();
                            String uuidStr = vnext.get("users").get(0).get("id").asText();
                            instance.getOutboundManager().addHandler(new VlessOutboundHandler(tag, addr, port, UUID.fromString(uuidStr)));
                        } else {
                            logger.warn("VLESS outbound [{}] missing valid vnext settings", tag);
                        }
                        break;
                    case "trojan":
                        if (settings != null && settings.has("servers") && settings.get("servers").isArray()) {
                            JsonNode server = settings.get("servers").get(0);
                            String addr = server.get("address").asText();
                            int port = server.get("port").asInt();
                            String password = server.get("password").asText();

                            boolean tls = false;
                            String sni = null;
                            boolean allowInsecure = false;

                            JsonNode streamSettings = oc.getStreamSettings();
                            if (streamSettings != null) {
                                if (streamSettings.has("security") && "tls".equalsIgnoreCase(streamSettings.get("security").asText())) {
                                    tls = true;
                                }
                                if (streamSettings.has("tlsSettings")) {
                                    JsonNode tlsSettings = streamSettings.get("tlsSettings");
                                    if (tlsSettings.has("serverName")) {
                                        sni = tlsSettings.get("serverName").asText();
                                    }
                                    if (tlsSettings.has("allowInsecure")) {
                                        allowInsecure = tlsSettings.get("allowInsecure").asBoolean();
                                    }
                                }
                            }

                            instance.getOutboundManager().addHandler(
                                    new TrojanOutboundHandler(tag, addr, port, password, tls, sni, allowInsecure)
                            );
                        } else {
                            logger.warn("Trojan outbound [{}] missing valid servers settings", tag);
                        }
                        break;
                    case "vmess":
                        if (settings != null && settings.has("vnext") && settings.get("vnext").isArray()) {
                            JsonNode vnext = settings.get("vnext").get(0);
                            String addr = vnext.get("address").asText();
                            int port = vnext.get("port").asInt();
                            String uuidStr = vnext.get("users").get(0).get("id").asText();
                            instance.getOutboundManager().addHandler(
                                    new com.v2ray.proxy.vmess.VmessOutboundHandler(tag, addr, port, UUID.fromString(uuidStr))
                            );
                        } else {
                            logger.warn("VMess outbound [{}] missing valid vnext settings", tag);
                        }
                        break;
                    case "shadowsocks":
                    case "ss":
                        if (settings != null && settings.has("servers") && settings.get("servers").isArray()) {
                            JsonNode server = settings.get("servers").get(0);
                            String addr = server.get("address").asText();
                            int port = server.get("port").asInt();
                            String method = server.has("method") ? server.get("method").asText() : "aes-128-gcm";
                            String password = server.get("password").asText();
                            instance.getOutboundManager().addHandler(
                                    new com.v2ray.proxy.shadowsocks.ShadowsocksOutboundHandler(tag, addr, port, method, password)
                            );
                        } else {
                            logger.warn("Shadowsocks outbound [{}] missing valid servers settings", tag);
                        }
                        break;
                    default:
                        logger.warn("Unsupported outbound protocol: {}, falling back to freedom", protocol);
                        instance.getOutboundManager().addHandler(new FreedomOutboundHandler(tag));
                        break;
                }
            }
        }

        // 2. Configure Router
        if (config.getRouting() != null && config.getRouting().getRules() != null) {
            DefaultRouter router = (DefaultRouter) instance.getRouter();
            for (RoutingRuleConfig rrc : config.getRouting().getRules()) {
                RoutingRule rule = new RoutingRule(rrc.getOutboundTag());
                if (rrc.getDomain() != null) {
                    rule.getDomains().addAll(rrc.getDomain());
                }
                if (rrc.getIp() != null) {
                    rule.getIpCidrs().addAll(rrc.getIp());
                }
                if (rrc.getInboundTag() != null) {
                    rule.getInboundTags().addAll(rrc.getInboundTag());
                }
                if (rrc.getPort() != null) {
                    String[] portParts = rrc.getPort().split(",");
                    for (String part : portParts) {
                        try {
                            rule.getPorts().add(Integer.parseInt(part.trim()));
                        } catch (NumberFormatException ignored) {}
                    }
                }
                router.addRule(rule);
            }
        }

        // 3. Configure Inbounds
        if (config.getInbounds() != null) {
            for (InboundConfig ic : config.getInbounds()) {
                String protocol = ic.getProtocol() == null ? "socks" : ic.getProtocol().toLowerCase();
                String tag = ic.getTag() == null ? protocol : ic.getTag();
                JsonNode settings = ic.getSettings();

                switch (protocol) {
                    case "socks":
                    case "socks5":
                        instance.getInboundManager().addHandler(new Socks5InboundHandler(tag, ic.getListen(), ic.getPort()));
                        break;
                    case "http":
                        instance.getInboundManager().addHandler(new HttpProxyInboundHandler(tag, ic.getListen(), ic.getPort()));
                        break;
                    case "vless":
                        VlessInboundHandler vlessHandler = new VlessInboundHandler(tag, ic.getListen(), ic.getPort());
                        if (settings != null && settings.has("clients") && settings.get("clients").isArray()) {
                            for (JsonNode client : settings.get("clients")) {
                                if (client.has("id")) {
                                    vlessHandler.addAllowedUser(UUID.fromString(client.get("id").asText()));
                                }
                            }
                        }
                        instance.getInboundManager().addHandler(vlessHandler);
                        break;
                    case "trojan":
                        TrojanInboundHandler trojanHandler = new TrojanInboundHandler(tag, ic.getListen(), ic.getPort());
                        if (settings != null && settings.has("clients") && settings.get("clients").isArray()) {
                            for (JsonNode client : settings.get("clients")) {
                                if (client.has("password")) {
                                    trojanHandler.addPassword(client.get("password").asText());
                                }
                            }
                        }
                        instance.getInboundManager().addHandler(trojanHandler);
                        break;
                    case "vmess":
                        com.v2ray.proxy.vmess.VmessInboundHandler vmessIn =
                                new com.v2ray.proxy.vmess.VmessInboundHandler(tag, ic.getListen(), ic.getPort());
                        if (settings != null && settings.has("clients") && settings.get("clients").isArray()) {
                            for (JsonNode client : settings.get("clients")) {
                                if (client.has("id")) {
                                    vmessIn.addAllowedUser(UUID.fromString(client.get("id").asText()));
                                }
                            }
                        }
                        instance.getInboundManager().addHandler(vmessIn);
                        break;
                    case "shadowsocks":
                    case "ss":
                        String ssMethod = (settings != null && settings.has("method")) ? settings.get("method").asText() : "aes-128-gcm";
                        String ssPassword = (settings != null && settings.has("password")) ? settings.get("password").asText() : "";
                        instance.getInboundManager().addHandler(
                                new com.v2ray.proxy.shadowsocks.ShadowsocksInboundHandler(tag, ic.getListen(), ic.getPort(), ssMethod, ssPassword)
                        );
                        break;
                    case "dokodemo-door":
                    case "dokodemo":
                        String targetAddr = (settings != null && settings.has("address")) ? settings.get("address").asText() : "127.0.0.1";
                        int targetPort = (settings != null && settings.has("port")) ? settings.get("port").asInt() : 80;
                        instance.getInboundManager().addHandler(
                                new com.v2ray.proxy.dokodemo.DokodemoInboundHandler(tag, ic.getListen(), ic.getPort(), targetAddr, targetPort)
                        );
                        break;
                    default:
                        logger.warn("Inbound protocol [{}] not supported, skipping", protocol);
                        break;
                }
            }
        }

        return instance;
    }
}
