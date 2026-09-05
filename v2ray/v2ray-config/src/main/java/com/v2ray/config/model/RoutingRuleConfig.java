package com.v2ray.config.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RoutingRuleConfig {
    private String outboundTag;
    private String port;
    private List<String> domain = new ArrayList<>();
    private List<String> ip = new ArrayList<>();
    private List<String> inboundTag = new ArrayList<>();

    public String getOutboundTag() {
        return outboundTag;
    }

    public void setOutboundTag(String outboundTag) {
        this.outboundTag = outboundTag;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public List<String> getDomain() {
        return domain;
    }

    public void setDomain(List<String> domain) {
        this.domain = domain;
    }

    public List<String> getIp() {
        return ip;
    }

    public void setIp(List<String> ip) {
        this.ip = ip;
    }

    public List<String> getInboundTag() {
        return inboundTag;
    }

    public void setInboundTag(List<String> inboundTag) {
        this.inboundTag = inboundTag;
    }
}
