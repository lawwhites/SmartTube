package com.v2ray.transport.internet.websocket;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class WebSocketTransportTest {

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private int port;

    @BeforeEach
    public void setUp() throws Exception {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(2);

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
         .channel(NioServerSocketChannel.class)
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 WebSocketServerTransport.attach(ch, "/echo", (channel) -> {
                     channel.pipeline().addLast("echo-handler", new SimpleChannelInboundHandler<ByteBuf>() {
                         @Override
                         protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                             ctx.writeAndFlush(msg.retain());
                         }
                     });
                 });
             }
         });

        serverChannel = b.bind("127.0.0.1", 0).sync().channel();
        port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @AfterEach
    public void tearDown() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    @Test
    public void testWebSocketByteStreaming() throws Exception {
        CompletableFuture<String> receivedFuture = new CompletableFuture<>();

        Bootstrap b = new Bootstrap();
        b.group(workerGroup)
         .channel(NioSocketChannel.class)
         .handler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 URI uri = URI.create("ws://127.0.0.1:" + port + "/echo");
                 WebSocketClientTransport.attach(ch, uri, (channel) -> {
                     channel.pipeline().addLast("client-receiver", new SimpleChannelInboundHandler<ByteBuf>() {
                         @Override
                         protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                             byte[] bytes = new byte[msg.readableBytes()];
                             msg.readBytes(bytes);
                             receivedFuture.complete(new String(bytes, StandardCharsets.UTF_8));
                         }
                     });

                     // Send test payload
                     channel.writeAndFlush(Unpooled.copiedBuffer("Hello V2Ray WebSocket!", StandardCharsets.UTF_8));
                 });
             }
         });

        Channel clientChannel = b.connect("127.0.0.1", port).sync().channel();
        String result = receivedFuture.get(5, TimeUnit.SECONDS);

        assertEquals("Hello V2Ray WebSocket!", result);
        clientChannel.close();
    }
}
