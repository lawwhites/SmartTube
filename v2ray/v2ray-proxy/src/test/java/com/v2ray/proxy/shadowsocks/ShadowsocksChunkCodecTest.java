package com.v2ray.proxy.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class ShadowsocksChunkCodecTest {

    @Test
    void testCodecRoundtrip() {
        String method = ShadowsocksCrypto.METHOD_AES_128_GCM;
        byte[] subkey = new byte[16];
        for (int i = 0; i < 16; i++) subkey[i] = (byte) (i + 1);

        EmbeddedChannel enc = new EmbeddedChannel(new ShadowsocksChunkCodec.Encoder(method, subkey));
        EmbeddedChannel dec = new EmbeddedChannel(new ShadowsocksChunkCodec.Decoder(method, subkey));

        String chunk1 = "Shadowsocks AEAD chunk 1 payload";
        String chunk2 = "Shadowsocks AEAD chunk 2 payload, larger size!";

        enc.writeOutbound(Unpooled.copiedBuffer(chunk1, StandardCharsets.UTF_8));
        enc.writeOutbound(Unpooled.copiedBuffer(chunk2, StandardCharsets.UTF_8));

        while (true) {
            ByteBuf out = enc.readOutbound();
            if (out == null) break;
            dec.writeInbound(out);
        }

        ByteBuf res1 = dec.readInbound();
        assertNotNull(res1);
        assertEquals(chunk1, res1.toString(StandardCharsets.UTF_8));
        res1.release();

        ByteBuf res2 = dec.readInbound();
        assertNotNull(res2);
        assertEquals(chunk2, res2.toString(StandardCharsets.UTF_8));
        res2.release();
    }
}
