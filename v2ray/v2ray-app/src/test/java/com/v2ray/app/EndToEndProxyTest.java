package com.v2ray.app;

import com.v2ray.config.ConfigLoader;
import com.v2ray.config.model.InboundConfig;
import com.v2ray.config.model.OutboundConfig;
import com.v2ray.config.model.V2RayConfig;
import com.v2ray.core.instance.V2RayInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EndToEndProxyTest {

    private ServerSocket echoServer;
    private int echoPort;
    private V2RayInstance v2ray;
    private final int socksPort = 20808;

    @BeforeEach
    public void setUp() throws Exception {
        // 1. Start a local echo server
        echoServer = new ServerSocket(0);
        echoPort = echoServer.getLocalPort();
        new Thread(() -> {
            try {
                Socket client = echoServer.accept();
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();
                byte[] buf = new byte[1024];
                int n = in.read(buf);
                if (n > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
                client.close();
            } catch (Exception ignored) {}
        }).start();

        // 2. Start V2Ray instance with SOCKS5 inbound and Freedom outbound
        V2RayConfig config = new V2RayConfig();

        InboundConfig in = new InboundConfig();
        in.setTag("socks-test");
        in.setListen("127.0.0.1");
        in.setPort(socksPort);
        in.setProtocol("socks");
        config.getInbounds().add(in);

        OutboundConfig out = new OutboundConfig();
        out.setTag("direct");
        out.setProtocol("freedom");
        config.getOutbounds().add(out);

        v2ray = ConfigLoader.createInstance(config);
        v2ray.start();
        Thread.sleep(200); // Wait for binding
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (v2ray != null) {
            v2ray.close();
        }
        if (echoServer != null) {
            echoServer.close();
        }
    }

    @Test
    public void testSocks5DirectProxy() throws Exception {
        // Connect to the echo server through Java's native SOCKS proxy client
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort));
        Socket socket = new Socket(proxy);
        socket.connect(new InetSocketAddress("127.0.0.1", echoPort), 5000);

        assertTrue(socket.isConnected());

        String message = "Hello V2Ray Java Core!";
        OutputStream out = socket.getOutputStream();
        out.write(message.getBytes(StandardCharsets.UTF_8));
        out.flush();

        InputStream in = socket.getInputStream();
        byte[] response = new byte[1024];
        int read = in.read(response);

        String result = new String(response, 0, read, StandardCharsets.UTF_8);
        assertEquals(message, result);

        socket.close();
    }
}
