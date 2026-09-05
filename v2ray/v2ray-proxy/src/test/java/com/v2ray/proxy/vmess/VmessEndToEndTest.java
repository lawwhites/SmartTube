package com.v2ray.proxy.vmess;

import com.v2ray.common.dispatcher.Dispatcher;
import com.v2ray.common.net.Destination;
import com.v2ray.common.net.Network;
import com.v2ray.common.relay.RelayHandler;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class VmessEndToEndTest {

    private EventLoopGroup group;
    private Channel echoServerChannel;
    private int echoPort;

    private VmessInboundHandler inboundHandler;
    private int vmessPort;
    private final UUID testUser = UUID.randomUUID();

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
                             ctx.writeAndFlush(msg); // Echo back
                         }
                     });
                 }
             });
        echoServerChannel = echoB.bind("127.0.0.1", 0).sync().channel();
        echoPort = ((InetSocketAddress) echoServerChannel.localAddress()).getPort();

        // 2. Freedom outbound for forwarding from inbound to echo server
        com.v2ray.proxy.freedom.FreedomOutboundHandler freedom = new com.v2ray.proxy.freedom.FreedomOutboundHandler("direct");
        freedom.start();

        // 3. VMess Inbound server
        inboundHandler = new VmessInboundHandler("vmess-in", "127.0.0.1", 0);
        inboundHandler.addAllowedUser(testUser);
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
        vmessPort = inboundHandler.getPort();
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
    void testVmessRelay() throws Exception {
        VmessOutboundHandler outboundHandler = new VmessOutboundHandler("vmess-out", "127.0.0.1", vmessPort, testUser);
        outboundHandler.start();

        CompletableFuture<String> responseFuture = new CompletableFuture<>();

        // Start a dummy local client socket
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

        // Connect user client to clientEntryPort
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
        String testMessage = "Hello VMess AEAD Full Stack Echo!";
        clientConn.writeAndFlush(Unpooled.copiedBuffer(testMessage, StandardCharsets.UTF_8));

        String echoed = responseFuture.get(5, TimeUnit.SECONDS);
        assertEquals(testMessage, echoed);

        clientConn.close();
        clientEntryChannel.close();
        outboundHandler.close();
    }
}
