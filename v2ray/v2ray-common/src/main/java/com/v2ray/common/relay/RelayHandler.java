package com.v2ray.common.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bidirectional relay handler between inbound and outbound channels.
 * Supports backpressure (flow control) using Netty's channel writability signals.
 */
public class RelayHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(RelayHandler.class);

    private final Channel targetChannel;
    private final String name;

    public RelayHandler(Channel targetChannel, String name) {
        this.targetChannel = targetChannel;
        this.name = name;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (targetChannel.isActive()) {
            targetChannel.write(msg).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    logger.warn("[{}] Failed to forward data to target: {}", name, future.cause().getMessage());
                    closeOnFlush(ctx.channel());
                }
            });
        } else {
            ReferenceCountUtil.release(msg);
            closeOnFlush(ctx.channel());
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        if (targetChannel.isActive()) {
            targetChannel.flush();
        }
        ctx.fireChannelReadComplete();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        boolean writable = ctx.channel().isWritable();
        targetChannel.config().setAutoRead(writable);
        logger.debug("[{}] Writability changed: {}, setting peer autoRead to {}", name, writable, writable);
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.debug("[{}] Channel inactive, closing target channel", name);
        closeOnFlush(targetChannel);
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.warn("[{}] Exception caught: {}", name, cause.getMessage());
        closeOnFlush(ctx.channel());
    }

    public static void closeOnFlush(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
