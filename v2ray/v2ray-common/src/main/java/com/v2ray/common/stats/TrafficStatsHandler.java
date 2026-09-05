package com.v2ray.common.stats;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * Netty duplex handler that counts uplink (written) and downlink (read) bytes.
 */
public class TrafficStatsHandler extends ChannelDuplexHandler {
    private final Counter uplinkCounter;
    private final Counter downlinkCounter;

    public TrafficStatsHandler(Counter uplinkCounter, Counter downlinkCounter) {
        this.uplinkCounter = uplinkCounter;
        this.downlinkCounter = downlinkCounter;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (downlinkCounter != null && msg instanceof ByteBuf) {
            downlinkCounter.add(((ByteBuf) msg).readableBytes());
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (uplinkCounter != null && msg instanceof ByteBuf) {
            uplinkCounter.add(((ByteBuf) msg).readableBytes());
        }
        super.write(ctx, msg, promise);
    }
}
