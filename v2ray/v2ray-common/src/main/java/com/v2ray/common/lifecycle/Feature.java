package com.v2ray.common.lifecycle;

/**
 * Feature is a core subsystem in V2Ray instance (e.g. Router, InboundManager, OutboundManager, Dispatcher).
 * Equivalent to features.Feature in Go.
 */
public interface Feature extends Lifecycle {
    Class<? extends Feature> getFeatureType();
}
