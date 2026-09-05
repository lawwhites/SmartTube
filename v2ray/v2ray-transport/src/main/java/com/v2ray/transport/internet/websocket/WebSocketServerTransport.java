package com.v2ray.transport.internet.websocket;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.ServerHandshakeStateEvent;

import java.util.function.Consumer;

/**
 * Helper to install WebSocket server handshake and framing on a Netty server channel.
 */
public class WebSocketServerTransport {

    public static void attach(Channel channel, String websocketPath, Consumer<Channel> onHandshakeComplete) {
        channel.pipeline().addLast("ws-http-codec", new HttpServerCodec());
        channel.pipeline().addLast("ws-aggregator", new HttpObjectAggregator(65536));
        channel.pipeline().addLast("ws-protocol", new WebSocketServerProtocolHandler(websocketPath, null, true));

        channel.pipeline().addLast("ws-handshake-listener", new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt == ServerHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                    ctx.pipeline().addAfter("ws-protocol", "ws-frame-codec", new WebSocketFrameCodec());
                    ctx.pipeline().remove(this);
                    if (onHandshakeComplete != null) {
                        onHandshakeComplete.accept(ctx.channel());
                    }
                } else {
                    super.userEventTriggered(ctx, evt);
                }
            }
        });
    }
}
