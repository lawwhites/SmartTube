package com.v2ray.router;

import com.v2ray.common.lifecycle.Feature;
import com.v2ray.common.net.Destination;

/**
 * Router determines which outbound tag to use for a given destination and inbound tag.
 * Equivalent to features/routing.Router in Go.
 */
public interface Router extends Feature {
    String route(Destination destination, String inboundTag);

    @Override
    default Class<? extends Feature> getFeatureType() {
        return Router.class;
    }
}
