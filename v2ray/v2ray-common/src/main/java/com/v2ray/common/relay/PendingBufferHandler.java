package com.v2ray.common.relay;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Temporarily buffers incoming data while the outbound connection is being established.
 * Once connected, flushes all buffered data to the target channel.
 */
public class PendingBufferHandler extends ChannelInboundHandlerAdapter {
    private final List<Object> buffer = new ArrayList<>();
    private ChannelHandlerContext context;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.context = ctx;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        buffer.add(msg);
    }

    public void flushTo(Channel targetChannel) {
        // Detach first: any data arriving after the flush must flow to the
        // relay handlers directly, otherwise this handler keeps swallowing it.
        if (context != null && context.pipeline().get(PendingBufferHandler.class) == this) {
            context.pipeline().remove(this);
        }
        if (buffer.isEmpty()) {
            return;
        }
        for (Object msg : buffer) {
            targetChannel.write(msg);
        }
        targetChannel.flush();
        buffer.clear();
    }

    public void releaseAll() {
        for (Object msg : buffer) {
            ReferenceCountUtil.release(msg);
        }
        buffer.clear();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        releaseAll();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        releaseAll();
        ctx.close();
    }
}
