package com.v2ray.app;

import com.v2ray.core.instance.V2RayInstance;
import com.v2ray.proxy.dokodemo.DokodemoInboundHandler;
import com.v2ray.proxy.freedom.FreedomOutboundHandler;
import com.v2ray.proxy.http.HttpProxyInboundHandler;
import com.v2ray.proxy.shadowsocks.ShadowsocksCrypto;
import com.v2ray.proxy.shadowsocks.ShadowsocksInboundHandler;
import com.v2ray.proxy.shadowsocks.ShadowsocksOutboundHandler;
import com.v2ray.proxy.trojan.TrojanHeader;
import com.v2ray.proxy.trojan.TrojanInboundHandler;
import com.v2ray.proxy.vless.VlessHeader;
import com.v2ray.proxy.vless.VlessInboundHandler;
import com.v2ray.proxy.vmess.VmessInboundHandler;
import com.v2ray.proxy.vmess.VmessOutboundHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ComprehensiveProtocolTest {

    private static ServerSocket echoServer;
    private static int echoPort;

    private static V2RayInstance v2ray;
    private static EventLoopGroup testGroup;

    private static final int HTTP_PORT = 20810;
    private static final int VLESS_PORT = 20811;
    private static final int TROJAN_PORT = 20812;
    private static final int VMESS_PORT = 20813;
    private static final int SS_PORT = 20814;
    private static final int DOKODEMO_PORT = 20815;

    private static final UUID VLESS_UUID = UUID.fromString("27848739-7e62-4138-9fd3-098a63964b6b");
    private static final UUID VMESS_UUID = UUID.fromString("6a2e4822-124b-47b2-bb0e-17cf3a8c3d9a");
    private static final String TROJAN_PASSWORD = "test-trojan-password";
    private static final String SS_PASSWORD = "test-ss-password";

    @BeforeAll
    public static void setUpAll() throws Exception {
        testGroup = new NioEventLoopGroup();

        // 1. Start echo server
        echoServer = new ServerSocket(0);
        echoPort = echoServer.getLocalPort();
        new Thread(() -> {
            try {
                while (!echoServer.isClosed()) {
                    Socket client = echoServer.accept();
                    new Thread(() -> {
                        try {
                            InputStream in = client.getInputStream();
                            OutputStream out = client.getOutputStream();
                            byte[] buf = new byte[2048];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                out.write(buf, 0, n);
                                out.flush();
                            }
                        } catch (Exception ignored) {}
                    }).start();
                }
            } catch (Exception ignored) {}
        }).start();

        // 2. Start V2Ray instance with full suite of inbounds
        v2ray = new V2RayInstance();
        v2ray.getOutboundManager().addHandler(new FreedomOutboundHandler("direct"));

        // HTTP Inbound
        v2ray.getInboundManager().addHandler(new HttpProxyInboundHandler("http-in", "127.0.0.1", HTTP_PORT));

        // VLESS Inbound
        VlessInboundHandler vless = new VlessInboundHandler("vless-in", "127.0.0.1", VLESS_PORT);
        vless.addAllowedUser(VLESS_UUID);
        v2ray.getInboundManager().addHandler(vless);

        // Trojan Inbound
        TrojanInboundHandler trojan = new TrojanInboundHandler("trojan-in", "127.0.0.1", TROJAN_PORT);
        trojan.addPassword(TROJAN_PASSWORD);
        v2ray.getInboundManager().addHandler(trojan);

        // VMess Inbound
        VmessInboundHandler vmess = new VmessInboundHandler("vmess-in", "127.0.0.1", VMESS_PORT);
        vmess.addAllowedUser(VMESS_UUID);
        v2ray.getInboundManager().addHandler(vmess);

        // Shadowsocks Inbound
        ShadowsocksInboundHandler ss = new ShadowsocksInboundHandler("ss-in", "127.0.0.1", SS_PORT,
                ShadowsocksCrypto.METHOD_AES_128_GCM, SS_PASSWORD);
        v2ray.getInboundManager().addHandler(ss);

        // Dokodemo Inbound
        DokodemoInboundHandler dokodemo = new DokodemoInboundHandler("dokodemo-in", "127.0.0.1", DOKODEMO_PORT,
                "127.0.0.1", echoPort);
        v2ray.getInboundManager().addHandler(dokodemo);

        v2ray.start();
        Thread.sleep(300);
    }

    @AfterAll
    public static void tearDownAll() throws Exception {
        if (v2ray != null) {
            v2ray.close();
        }
        if (echoServer != null) {
            echoServer.close();
        }
        if (testGroup != null) {
            testGroup.shutdownGracefully();
        }
    }

    @Test
    public void testHttpConnectProxy() throws Exception {
        Socket socket = new Socket("127.0.0.1", HTTP_PORT);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        String connectRequest = "CONNECT 127.0.0.1:" + echoPort + " HTTP/1.1\r\nHost: 127.0.0.1:" + echoPort + "\r\n\r\n";
        out.write(connectRequest.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        byte[] respBuf = new byte[1024];
        int read = in.read(respBuf);
        String response = new String(respBuf, 0, read, StandardCharsets.US_ASCII);
        assertTrue(response.contains("200 Connection Established"));

        String payload = "Hello through HTTP Tunnel!";
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.flush();

        byte[] echoBuf = new byte[1024];
        int echoRead = in.read(echoBuf);
        assertEquals(payload, new String(echoBuf, 0, echoRead, StandardCharsets.UTF_8));

        socket.close();
    }

    @Test
    public void testVlessProxy() throws Exception {
        Socket socket = new Socket("127.0.0.1", VLESS_PORT);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        ByteBuf headerBuf = Unpooled.buffer();
        VlessHeader header = new VlessHeader(VLESS_UUID, VlessHeader.COMMAND_TCP,
                com.v2ray.common.net.Destination.tcp("127.0.0.1", echoPort));
        header.encodeRequest(headerBuf);

        byte[] headerBytes = new byte[headerBuf.readableBytes()];
        headerBuf.readBytes(headerBytes);
        out.write(headerBytes);
        out.flush();

        byte[] vlessResp = new byte[2];
        int read = in.read(vlessResp);
        assertEquals(2, read);
        assertEquals(0x00, vlessResp[0]);
        assertEquals(0x00, vlessResp[1]);

        String payload = "Hello through VLESS!";
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.flush();

        byte[] echoBuf = new byte[1024];
        int echoRead = in.read(echoBuf);
        assertEquals(payload, new String(echoBuf, 0, echoRead, StandardCharsets.UTF_8));

        socket.close();
    }

    @Test
    public void testTrojanProxy() throws Exception {
        Socket socket = new Socket("127.0.0.1", TROJAN_PORT);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        String hash = TrojanHeader.computePasswordHash(TROJAN_PASSWORD);
        TrojanHeader header = new TrojanHeader(hash, TrojanHeader.COMMAND_TCP,
                com.v2ray.common.net.Destination.tcp("127.0.0.1", echoPort));

        ByteBuf headerBuf = Unpooled.buffer();
        header.encode(headerBuf);

        byte[] headerBytes = new byte[headerBuf.readableBytes()];
        headerBuf.readBytes(headerBytes);
        out.write(headerBytes);

        String payload = "Hello through Trojan!";
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.flush();

        byte[] echoBuf = new byte[1024];
        int echoRead = in.read(echoBuf);
        assertEquals(payload, new String(echoBuf, 0, echoRead, StandardCharsets.UTF_8));

        socket.close();
    }

    @Test
    public void testVmessProxy() throws Exception {
        VmessOutboundHandler outbound = new VmessOutboundHandler("vmess-client", "127.0.0.1", VMESS_PORT, VMESS_UUID);
        outbound.start();

        CompletableFuture<String> res = new CompletableFuture<>();
        ServerBootstrap sb = new ServerBootstrap();
        sb.group(testGroup)
          .channel(NioServerSocketChannel.class)
          .childHandler(new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {
                  outbound.dispatch(com.v2ray.common.net.Destination.tcp("127.0.0.1", echoPort), ch);
              }
          });
        Channel sc = sb.bind("127.0.0.1", 0).sync().channel();
        int localPort = ((InetSocketAddress) sc.localAddress()).getPort();

        Bootstrap cb = new Bootstrap();
        cb.group(testGroup)
          .channel(NioSocketChannel.class)
          .handler(new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {
                  ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                      @Override
                      protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                          res.complete(msg.toString(StandardCharsets.UTF_8));
                      }
                  });
              }
          });

        Channel client = cb.connect("127.0.0.1", localPort).sync().channel();
        String payload = "Hello through VMess AEAD!";
        client.writeAndFlush(Unpooled.copiedBuffer(payload, StandardCharsets.UTF_8));

        assertEquals(payload, res.get(5, TimeUnit.SECONDS));

        client.close();
        sc.close();
        outbound.close();
    }

    @Test
    public void testShadowsocksProxy() throws Exception {
        ShadowsocksOutboundHandler outbound = new ShadowsocksOutboundHandler("ss-client", "127.0.0.1", SS_PORT,
                ShadowsocksCrypto.METHOD_AES_128_GCM, SS_PASSWORD);
        outbound.start();

        CompletableFuture<String> res = new CompletableFuture<>();
        ServerBootstrap sb = new ServerBootstrap();
        sb.group(testGroup)
          .channel(NioServerSocketChannel.class)
          .childHandler(new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {
                  outbound.dispatch(com.v2ray.common.net.Destination.tcp("127.0.0.1", echoPort), ch);
              }
          });
        Channel sc = sb.bind("127.0.0.1", 0).sync().channel();
        int localPort = ((InetSocketAddress) sc.localAddress()).getPort();

        Bootstrap cb = new Bootstrap();
        cb.group(testGroup)
          .channel(NioSocketChannel.class)
          .handler(new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {
                  ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                      @Override
                      protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                          res.complete(msg.toString(StandardCharsets.UTF_8));
                      }
                  });
              }
          });

        Channel client = cb.connect("127.0.0.1", localPort).sync().channel();
        String payload = "Hello through Shadowsocks SIP008!";
        client.writeAndFlush(Unpooled.copiedBuffer(payload, StandardCharsets.UTF_8));

        assertEquals(payload, res.get(5, TimeUnit.SECONDS));

        client.close();
        sc.close();
        outbound.close();
    }

    @Test
    public void testDokodemoProxy() throws Exception {
        Socket socket = new Socket("127.0.0.1", DOKODEMO_PORT);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        String payload = "Hello through Dokodemo Port Forwarding!";
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.flush();

        byte[] echoBuf = new byte[1024];
        int echoRead = in.read(echoBuf);
        assertEquals(payload, new String(echoBuf, 0, echoRead, StandardCharsets.UTF_8));

        socket.close();
    }
}
