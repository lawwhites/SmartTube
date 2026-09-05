package com.v2ray.proxy;

import com.v2ray.common.lifecycle.Lifecycle;
import com.v2ray.common.net.Destination;
import io.netty.channel.Channel;

/**
 * OutboundHandler dials the external server or next hop proxy,
 * and relays traffic to/from the inbound channel.
 */
public interface OutboundHandler extends Lifecycle {
    String getTag();
    void dispatch(Destination destination, Channel inboundChannel);
}
