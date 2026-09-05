package com.v2ray.proxy.vmess.aead;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class VMessKdfTest {

    @Test
    void testKDFVector() {
        byte[] key = "Demo Key for KDF Value Test".getBytes(StandardCharsets.UTF_8);
        byte[] result = VMessKdf.kdf(key,
                "Demo Path for KDF Value Test",
                "Demo Path for KDF Value Test2",
                "Demo Path for KDF Value Test3");

        StringBuilder sb = new StringBuilder();
        for (byte b : result) {
            sb.append(String.format("%02x", b));
        }

        assertEquals("53e9d7e1bd7bd25022b71ead07d8a596efc8a845c7888652fd684b4903dc8892", sb.toString());
    }

    @Test
    void testShakeDigestStreaming() {
        org.bouncycastle.crypto.digests.SHAKEDigest shake = new org.bouncycastle.crypto.digests.SHAKEDigest(128);
        byte[] nonce = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        shake.update(nonce, 0, nonce.length);

        byte[] out1 = new byte[2];
        shake.doOutput(out1, 0, 2);

        byte[] out2 = new byte[2];
        shake.doOutput(out2, 0, 2);

        assertFalse(out1[0] == 0 && out1[1] == 0 && out2[0] == 0 && out2[1] == 0);
    }
}
