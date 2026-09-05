package com.v2ray.proxy.vmess.aead;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class VMessAeadTest {

    @Test
    void testAuthIdRoundtrip() {
        UUID uuid = UUID.randomUUID();
        byte[] cmdKey = VMessAead.cmdKey(uuid);

        long now = System.currentTimeMillis() / 1000;
        byte[] authId = VMessAead.createAuthId(cmdKey, now);
        assertEquals(16, authId.length);

        long decodedTime = VMessAead.openAuthId(cmdKey, authId);
        assertEquals(now, decodedTime);
    }

    @Test
    void testHeaderSealOpenRoundtrip() {
        UUID uuid = UUID.randomUUID();
        byte[] cmdKey = VMessAead.cmdKey(uuid);

        byte[] originalPayload = "Hello VMess AEAD Header Payload Data!".getBytes(StandardCharsets.UTF_8);
        byte[] sealedHeader = VMessAead.sealHeader(cmdKey, originalPayload);

        // Header structure: authId (16) + lenEncrypted (18) + nonce (8) + payloadEncrypted (len + 16)
        assertTrue(sealedHeader.length >= 16 + 18 + 8 + 16);

        byte[] authId = Arrays.copyOfRange(sealedHeader, 0, 16);
        byte[] lenEncrypted = Arrays.copyOfRange(sealedHeader, 16, 34);
        byte[] nonce = Arrays.copyOfRange(sealedHeader, 34, 42);
        byte[] payloadEncrypted = Arrays.copyOfRange(sealedHeader, 42, sealedHeader.length);

        byte[] openedPayload = VMessAead.openHeader(cmdKey, authId, lenEncrypted, nonce, payloadEncrypted);
        assertArrayEquals(originalPayload, openedPayload);
    }

    @Test
    void testResponseHeaderSealOpenRoundtrip() {
        byte[] responseBodyKey = new byte[16];
        byte[] responseBodyIV = new byte[16];
        Arrays.fill(responseBodyKey, (byte) 0x11);
        Arrays.fill(responseBodyIV, (byte) 0x22);

        byte[] respPayload = new byte[]{0x55, 0x00, 0x00, 0x00};
        byte[] sealedResp = VMessAead.sealResponseHeader(responseBodyKey, responseBodyIV, respPayload);

        byte[] lenEnc = Arrays.copyOfRange(sealedResp, 0, 18);
        byte[] payloadEnc = Arrays.copyOfRange(sealedResp, 18, sealedResp.length);

        int len = VMessAead.openResponseHeaderLength(responseBodyKey, responseBodyIV, lenEnc);
        assertEquals(respPayload.length, len);

        byte[] opened = VMessAead.openResponseHeaderPayload(responseBodyKey, responseBodyIV, payloadEnc);
        assertArrayEquals(respPayload, opened);
    }
}
