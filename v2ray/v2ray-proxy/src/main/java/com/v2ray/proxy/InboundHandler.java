package com.v2ray.proxy;

import com.v2ray.common.dispatcher.Dispatcher;
import com.v2ray.common.lifecycle.Lifecycle;

/**
 * InboundHandler receives incoming connections from clients,
 * parses proxy protocols, and passes traffic to the Dispatcher.
 */
public interface InboundHandler extends Lifecycle {
    String getTag();
    int getPort();
    String getListen();
    void setDispatcher(Dispatcher dispatcher);
    default void setEventLoopGroups(io.netty.channel.EventLoopGroup bossGroup, io.netty.channel.EventLoopGroup workerGroup) {}
}
