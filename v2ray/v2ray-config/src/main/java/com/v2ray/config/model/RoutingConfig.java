package com.v2ray.config.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RoutingConfig {
    private String domainStrategy = "IPIfNonMatch";
    private List<RoutingRuleConfig> rules = new ArrayList<>();

    public String getDomainStrategy() {
        return domainStrategy;
    }

    public void setDomainStrategy(String domainStrategy) {
        this.domainStrategy = domainStrategy;
    }

    public List<RoutingRuleConfig> getRules() {
        return rules;
    }

    public void setRules(List<RoutingRuleConfig> rules) {
        this.rules = rules;
    }
}
