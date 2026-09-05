package com.v2ray.proxy.trojan;

import com.v2ray.common.net.Destination;
import com.v2ray.common.net.Network;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TrojanHeaderTest {

    @Test
    public void testPasswordHash() {
        String password = "password123";
        String hash = TrojanHeader.computePasswordHash(password);
        assertNotNull(hash);
        assertEquals(56, hash.length());
    }

    @Test
    public void testTrojanHeaderEncodeDecode() {
        String password = "my-secret-trojan-password";
        String hash = TrojanHeader.computePasswordHash(password);
        Destination dest = Destination.tcp("v2ray.com", 443);

        TrojanHeader original = new TrojanHeader(hash, TrojanHeader.COMMAND_TCP, dest);

        ByteBuf buf = Unpooled.buffer();
        original.encode(buf);

        TrojanHeader decoded = TrojanHeader.decode(buf);
        assertNotNull(decoded);
        assertEquals(hash, decoded.getHexPasswordHash());
        assertEquals(TrojanHeader.COMMAND_TCP, decoded.getCommand());
        assertEquals(dest, decoded.getDestination());
        assertEquals("v2ray.com", decoded.getDestination().getAddress());
        assertEquals(443, decoded.getDestination().getPort());
        assertEquals(Network.TCP, decoded.getDestination().getNetwork());
    }
}
