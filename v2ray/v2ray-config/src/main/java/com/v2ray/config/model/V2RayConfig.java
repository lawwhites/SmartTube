package com.v2ray.config.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class V2RayConfig {
    private List<InboundConfig> inbounds = new ArrayList<>();
    private List<OutboundConfig> outbounds = new ArrayList<>();
    private RoutingConfig routing;

    public List<InboundConfig> getInbounds() {
        return inbounds;
    }

    public void setInbounds(List<InboundConfig> inbounds) {
        this.inbounds = inbounds;
    }

    public List<OutboundConfig> getOutbounds() {
        return outbounds;
    }

    public void setOutbounds(List<OutboundConfig> outbounds) {
        this.outbounds = outbounds;
    }

    public RoutingConfig getRouting() {
        return routing;
    }

    public void setRouting(RoutingConfig routing) {
        this.routing = routing;
    }
}
