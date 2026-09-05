package com.v2ray.router;

import com.v2ray.common.net.Destination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class DefaultRouter implements Router {
    private static final Logger logger = LoggerFactory.getLogger(DefaultRouter.class);

    private final List<RoutingRule> rules = new ArrayList<>();
    private String defaultOutboundTag = "direct";
    private volatile boolean running = false;

    public DefaultRouter() {}

    public DefaultRouter(String defaultOutboundTag) {
        this.defaultOutboundTag = defaultOutboundTag;
    }

    public void addRule(RoutingRule rule) {
        rules.add(rule);
    }

    public void setDefaultOutboundTag(String defaultOutboundTag) {
        this.defaultOutboundTag = defaultOutboundTag;
    }

    @Override
    public String route(Destination destination, String inboundTag) {
        for (RoutingRule rule : rules) {
            if (rule.matches(destination, inboundTag)) {
                logger.debug("Routing matched rule to tag: {} for destination: {}", rule.getOutboundTag(), destination);
                return rule.getOutboundTag();
            }
        }
        logger.debug("Routing using default tag: {} for destination: {}", defaultOutboundTag, destination);
        return defaultOutboundTag;
    }

    @Override
    public void start() {
        running = true;
        logger.info("DefaultRouter started with {} rules, default outbound: {}", rules.size(), defaultOutboundTag);
    }

    @Override
    public void close() {
        running = false;
        rules.clear();
        logger.info("DefaultRouter closed");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
