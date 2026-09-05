package com.v2ray.proxy.dokodemo;

import com.v2ray.common.dispatcher.Dispatcher;
import com.v2ray.common.net.Destination;
import com.v2ray.proxy.freedom.FreedomOutboundHandler;
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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DokodemoInboundTest {

    private EventLoopGroup group;
    private Channel echoServerChannel;
    private int echoPort;

    private DokodemoInboundHandler dokodemo;
    private int dokodemoPort;

    @BeforeEach
    void setUp() throws Exception {
        group = new NioEventLoopGroup();

        // 1. Echo server
        ServerBootstrap echoB = new ServerBootstrap();
        echoB.group(group)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                         @Override
                         public void channelRead(ChannelHandlerContext ctx, Object msg) {
                             ctx.writeAndFlush(msg);
                         }
                     });
                 }
             });
        echoServerChannel = echoB.bind("127.0.0.1", 0).sync().channel();
        echoPort = ((InetSocketAddress) echoServerChannel.localAddress()).getPort();

        // 2. Freedom outbound for forwarding
        FreedomOutboundHandler freedom = new FreedomOutboundHandler("direct");
        freedom.start();

        // 3. Dokodemo Inbound forwarding to echo server
        dokodemo = new DokodemoInboundHandler("dokodemo-in", "127.0.0.1", 0, "127.0.0.1", echoPort);
        dokodemo.setDispatcher(new Dispatcher() {
            @Override
            public void dispatch(Destination destination, Channel inboundChannel, String inboundTag) {
                freedom.dispatch(destination, inboundChannel);
            }

            @Override
            public void start() {}

            @Override
            public void close() {
                freedom.close();
            }

            @Override
            public boolean isRunning() {
                return true;
            }
        });
        dokodemo.start();
        dokodemoPort = dokodemo.getPort();
    }

    @AfterEach
    void tearDown() {
        if (dokodemo != null) {
            dokodemo.close();
        }
        if (echoServerChannel != null) {
            echoServerChannel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    @Test
    void testDokodemoForwarding() throws Exception {
        CompletableFuture<String> responseFuture = new CompletableFuture<>();

        Bootstrap clientB = new Bootstrap();
        clientB.group(group)
               .channel(NioSocketChannel.class)
               .handler(new ChannelInitializer<SocketChannel>() {
                   @Override
                   protected void initChannel(SocketChannel ch) {
                       ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                           @Override
                           protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                               responseFuture.complete(msg.toString(StandardCharsets.UTF_8));
                           }
                       });
                   }
               });

        Channel clientConn = clientB.connect("127.0.0.1", dokodemoPort).sync().channel();
        String testData = "Hello Dokodemo Port Forwarding!";
        clientConn.writeAndFlush(Unpooled.copiedBuffer(testData, StandardCharsets.UTF_8));

        String result = responseFuture.get(5, TimeUnit.SECONDS);
        assertEquals(testData, result);

        clientConn.close();
    }
}
