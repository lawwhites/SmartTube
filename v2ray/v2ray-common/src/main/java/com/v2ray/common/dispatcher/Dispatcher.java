package com.v2ray.common.dispatcher;

import com.v2ray.common.lifecycle.Feature;
import com.v2ray.common.net.Destination;
import io.netty.channel.Channel;

/**
 * Dispatcher is responsible for routing and dispatching inbound traffic
 * to the appropriate outbound handler.
 * Equivalent to app/dispatcher.Dispatcher in Go.
 */
public interface Dispatcher extends Feature {
    /**
     * Dispatch an inbound connection to the appropriate outbound handler based on routing rules.
     *
     * @param destination the target destination
     * @param inboundChannel the inbound Netty channel
     * @param inboundTag the tag of the inbound handler that accepted this connection
     */
    void dispatch(Destination destination, Channel inboundChannel, String inboundTag);

    @Override
    default Class<? extends Feature> getFeatureType() {
        return Dispatcher.class;
    }
}
