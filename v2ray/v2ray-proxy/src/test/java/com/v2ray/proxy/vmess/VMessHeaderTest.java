package com.v2ray.proxy.vmess;

import com.v2ray.common.net.Destination;
import com.v2ray.common.net.Network;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class VMessHeaderTest {

    @Test
    void testRequestHeaderDomainRoundtrip() {
        VMessHeader.RequestHeader req = new VMessHeader.RequestHeader();
        req.responseHeader = 0x3c;
        req.destination = Destination.tcp("www.google.com", 443);
        req.userUuid = UUID.randomUUID();

        byte[] encoded = VMessHeader.encodeRequestPayload(req);
        VMessHeader.RequestHeader decoded = VMessHeader.decodeRequestPayload(encoded);

        assertEquals(req.version, decoded.version);
        assertArrayEquals(req.requestBodyIV, decoded.requestBodyIV);
        assertArrayEquals(req.requestBodyKey, decoded.requestBodyKey);
        assertEquals(req.responseHeader, decoded.responseHeader);
        assertEquals(req.option, decoded.option);
        assertEquals(req.security, decoded.security);
        assertEquals(req.command, decoded.command);
        assertEquals("www.google.com", decoded.destination.getAddress());
        assertEquals(443, decoded.destination.getPort());
        assertEquals(Network.TCP, decoded.destination.getNetwork());
    }

    @Test
    void testRequestHeaderIPv4Roundtrip() {
        VMessHeader.RequestHeader req = new VMessHeader.RequestHeader();
        req.responseHeader = 0x7a;
        req.destination = Destination.tcp("1.1.1.1", 853);
        req.userUuid = UUID.randomUUID();

        byte[] encoded = VMessHeader.encodeRequestPayload(req);
        VMessHeader.RequestHeader decoded = VMessHeader.decodeRequestPayload(encoded);

        assertEquals("1.1.1.1", decoded.destination.getAddress());
        assertEquals(853, decoded.destination.getPort());
    }
}
