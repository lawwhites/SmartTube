package com.v2ray.transport.internet.websocket;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler.ClientHandshakeStateEvent;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;

import java.net.URI;
import java.util.function.Consumer;

/**
 * Helper to install WebSocket client handshake and framing on a Netty channel.
 */
public class WebSocketClientTransport {

    public static void attach(Channel channel, URI uri, Consumer<Channel> onHandshakeComplete) {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set("Host", uri.getHost() + (uri.getPort() > 0 && uri.getPort() != 80 && uri.getPort() != 443 ? ":" + uri.getPort() : ""));
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

        channel.pipeline().addLast("ws-http-codec", new HttpClientCodec());
        channel.pipeline().addLast("ws-aggregator", new HttpObjectAggregator(65536));
        channel.pipeline().addLast("ws-protocol", new WebSocketClientProtocolHandler(
                uri,
                WebSocketVersion.V13,
                null,
                true,
                headers,
                65536,
                10000
        ));

        channel.pipeline().addLast("ws-handshake-listener", new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt == ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                    // Handshake completed, install the frame-to-byte codec
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
