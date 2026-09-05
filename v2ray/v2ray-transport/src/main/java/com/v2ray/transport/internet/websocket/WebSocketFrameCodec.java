package com.v2ray.transport.internet.websocket;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.util.List;

/**
 * Bridges Netty ByteBuf and WebSocket BinaryWebSocketFrame for transparent tunneling.
 */
public class WebSocketFrameCodec extends MessageToMessageCodec<WebSocketFrame, ByteBuf> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        out.add(new BinaryWebSocketFrame(msg.retain()));
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, WebSocketFrame frame, List<Object> out) {
        if (frame instanceof BinaryWebSocketFrame) {
            out.add(frame.content().retain());
        }
    }
}
