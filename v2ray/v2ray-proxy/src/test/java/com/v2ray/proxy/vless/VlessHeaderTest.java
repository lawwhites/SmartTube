package com.v2ray.proxy.vless;

import com.v2ray.common.net.Destination;
import com.v2ray.common.net.Network;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class VlessHeaderTest {

    @Test
    public void testVlessHeaderDomainEncodeDecode() {
        UUID uuid = UUID.randomUUID();
        Destination dest = Destination.tcp("example.com", 443);
        VlessHeader original = new VlessHeader(uuid, VlessHeader.COMMAND_TCP, dest);

        ByteBuf buf = Unpooled.buffer();
        original.encodeRequest(buf);

        VlessHeader decoded = VlessHeader.decodeRequest(buf);
        assertNotNull(decoded);
        assertEquals(uuid, decoded.getUuid());
        assertEquals(VlessHeader.COMMAND_TCP, decoded.getCommand());
        assertEquals(dest, decoded.getDestination());
        assertEquals(Network.TCP, decoded.getDestination().getNetwork());
        assertEquals("example.com", decoded.getDestination().getAddress());
        assertEquals(443, decoded.getDestination().getPort());
    }

    @Test
    public void testVlessHeaderIpv4EncodeDecode() {
        UUID uuid = UUID.randomUUID();
        Destination dest = Destination.tcp("1.1.1.1", 80);
        VlessHeader original = new VlessHeader(uuid, VlessHeader.COMMAND_TCP, dest);

        ByteBuf buf = Unpooled.buffer();
        original.encodeRequest(buf);

        VlessHeader decoded = VlessHeader.decodeRequest(buf);
        assertNotNull(decoded);
        assertEquals(uuid, decoded.getUuid());
        assertEquals(dest, decoded.getDestination());
    }

    @Test
    public void testVlessResponseEncode() {
        ByteBuf buf = Unpooled.buffer();
        VlessHeader.encodeResponse(buf);
        assertEquals(2, buf.readableBytes());
        assertEquals(0x00, buf.readByte());
        assertEquals(0x00, buf.readByte());
    }
}
