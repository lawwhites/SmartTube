package com.v2ray.proxy.vmess;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class VMessChunkCodecTest {

    @Test
    void testChunkRoundtripWithMasking() {
        byte[] key = new byte[16];
        byte[] iv = new byte[16];
        for (int i = 0; i < 16; i++) {
            key[i] = (byte) (i + 1);
            iv[i] = (byte) (i + 10);
        }

        EmbeddedChannel encChannel = new EmbeddedChannel(new VMessChunkCodec.ChunkEncoder(key, iv, true));
        EmbeddedChannel decChannel = new EmbeddedChannel(new VMessChunkCodec.ChunkDecoder(key, iv, true));

        String testData1 = "First chunk of VMess payload!";
        String testData2 = "Second chunk of VMess streaming data!";

        encChannel.writeOutbound(Unpooled.copiedBuffer(testData1, StandardCharsets.UTF_8));
        encChannel.writeOutbound(Unpooled.copiedBuffer(testData2, StandardCharsets.UTF_8));

        while (true) {
            ByteBuf encBuf = encChannel.readOutbound();
            if (encBuf == null) break;
            decChannel.writeInbound(encBuf);
        }

        ByteBuf dec1 = decChannel.readInbound();
        assertNotNull(dec1);
        assertEquals(testData1, dec1.toString(StandardCharsets.UTF_8));
        dec1.release();

        ByteBuf dec2 = decChannel.readInbound();
        assertNotNull(dec2);
        assertEquals(testData2, dec2.toString(StandardCharsets.UTF_8));
        dec2.release();
    }

    @Test
    void testChunkRoundtripWithoutMasking() {
        byte[] key = new byte[16];
        byte[] iv = new byte[16];

        EmbeddedChannel encChannel = new EmbeddedChannel(new VMessChunkCodec.ChunkEncoder(key, iv, false));
        EmbeddedChannel decChannel = new EmbeddedChannel(new VMessChunkCodec.ChunkDecoder(key, iv, false));

        String testData = "Unmasked VMess chunk data test";
        encChannel.writeOutbound(Unpooled.copiedBuffer(testData, StandardCharsets.UTF_8));

        ByteBuf encBuf = encChannel.readOutbound();
        assertNotNull(encBuf);
        decChannel.writeInbound(encBuf);

        ByteBuf dec = decChannel.readInbound();
        assertNotNull(dec);
        assertEquals(testData, dec.toString(StandardCharsets.UTF_8));
        dec.release();
    }
}
