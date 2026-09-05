package com.v2ray.proxy.shadowsocks;

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

public class ShadowsocksEndToEndTest {

    private EventLoopGroup group;
    private Channel echoServerChannel;
    private int echoPort;

    private ShadowsocksInboundHandler inboundHandler;
    private int ssPort;
    private final String testPassword = "testSSPassword2026";
    private final String testMethod = ShadowsocksCrypto.METHOD_AES_128_GCM;

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
                             ctx.writeAndFlush(msg); // Echo
                         }
                     });
                 }
             });
        echoServerChannel = echoB.bind("127.0.0.1", 0).sync().channel();
        echoPort = ((InetSocketAddress) echoServerChannel.localAddress()).getPort();

        // 2. Freedom outbound for forwarding to echo server
        FreedomOutboundHandler freedom = new FreedomOutboundHandler("direct");
        freedom.start();

        // 3. Shadowsocks Inbound server
        inboundHandler = new ShadowsocksInboundHandler("ss-in", "127.0.0.1", 0, testMethod, testPassword);
        inboundHandler.setDispatcher(new Dispatcher() {
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
        inboundHandler.start();
        ssPort = inboundHandler.getPort();
    }

    @AfterEach
    void tearDown() {
        if (inboundHandler != null) {
            inboundHandler.close();
        }
        if (echoServerChannel != null) {
            echoServerChannel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    @Test
    void testShadowsocksRelay() throws Exception {
        ShadowsocksOutboundHandler outboundHandler =
                new ShadowsocksOutboundHandler("ss-out", "127.0.0.1", ssPort, testMethod, testPassword);
        outboundHandler.start();

        CompletableFuture<String> responseFuture = new CompletableFuture<>();

        // Local client entry
        ServerBootstrap clientEntryB = new ServerBootstrap();
        clientEntryB.group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            outboundHandler.dispatch(Destination.tcp("127.0.0.1", echoPort), ch);
                        }
                    });
        Channel clientEntryChannel = clientEntryB.bind("127.0.0.1", 0).sync().channel();
        int clientEntryPort = ((InetSocketAddress) clientEntryChannel.localAddress()).getPort();

        // Connect user client
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

        Channel clientConn = clientB.connect("127.0.0.1", clientEntryPort).sync().channel();
        String testMessage = "Hello Shadowsocks AEAD Echo Test!";
        clientConn.writeAndFlush(Unpooled.copiedBuffer(testMessage, StandardCharsets.UTF_8));

        String echoed = responseFuture.get(5, TimeUnit.SECONDS);
        assertEquals(testMessage, echoed);

        clientConn.close();
        clientEntryChannel.close();
        outboundHandler.close();
    }
}
