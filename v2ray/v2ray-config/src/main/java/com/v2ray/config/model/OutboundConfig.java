package com.v2ray.config.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OutboundConfig {
    private String tag = "direct";
    private String protocol = "freedom";
    private JsonNode settings;
    private JsonNode streamSettings;

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public JsonNode getSettings() {
        return settings;
    }

    public void setSettings(JsonNode settings) {
        this.settings = settings;
    }

    public JsonNode getStreamSettings() {
        return streamSettings;
    }

    public void setStreamSettings(JsonNode streamSettings) {
        this.streamSettings = streamSettings;
    }
}
