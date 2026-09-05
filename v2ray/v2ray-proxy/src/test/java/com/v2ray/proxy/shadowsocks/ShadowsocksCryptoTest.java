package com.v2ray.proxy.shadowsocks;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class ShadowsocksCryptoTest {

    @Test
    void testAes128GcmRoundtrip() {
        String method = ShadowsocksCrypto.METHOD_AES_128_GCM;
        int keySize = ShadowsocksCrypto.getKeySize(method);
        byte[] masterKey = ShadowsocksCrypto.passwordToKey("mySecretPassword123", keySize);
        assertEquals(16, masterKey.length);

        byte[] salt = new byte[16];
        for (int i = 0; i < 16; i++) salt[i] = (byte) (i + 1);

        byte[] subkey = ShadowsocksCrypto.deriveSubkey(masterKey, salt, keySize);
        assertEquals(16, subkey.length);

        byte[] nonce = ShadowsocksCrypto.generateNonce(0);
        byte[] plaintext = "Hello Shadowsocks AEAD AES-128-GCM!".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = ShadowsocksCrypto.encrypt(method, subkey, nonce, plaintext);
        assertEquals(plaintext.length + 16, ciphertext.length);

        byte[] decrypted = ShadowsocksCrypto.decrypt(method, subkey, nonce, ciphertext);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    void testAes256GcmRoundtrip() {
        String method = ShadowsocksCrypto.METHOD_AES_256_GCM;
        int keySize = ShadowsocksCrypto.getKeySize(method);
        byte[] masterKey = ShadowsocksCrypto.passwordToKey("myStrongMasterKey256", keySize);
        assertEquals(32, masterKey.length);

        byte[] salt = new byte[32];
        for (int i = 0; i < 32; i++) salt[i] = (byte) (i + 5);

        byte[] subkey = ShadowsocksCrypto.deriveSubkey(masterKey, salt, keySize);
        assertEquals(32, subkey.length);

        byte[] nonce = ShadowsocksCrypto.generateNonce(42);
        byte[] plaintext = "High Security 256-bit AEAD Payload!".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = ShadowsocksCrypto.encrypt(method, subkey, nonce, plaintext);
        byte[] decrypted = ShadowsocksCrypto.decrypt(method, subkey, nonce, ciphertext);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    void testChaCha20Poly1305Roundtrip() {
        String method = ShadowsocksCrypto.METHOD_CHACHA20_POLY1305;
        int keySize = ShadowsocksCrypto.getKeySize(method);
        byte[] masterKey = ShadowsocksCrypto.passwordToKey("chachaKeyPassword", keySize);
        assertEquals(32, masterKey.length);

        byte[] salt = new byte[32];
        byte[] subkey = ShadowsocksCrypto.deriveSubkey(masterKey, salt, keySize);

        byte[] nonce = ShadowsocksCrypto.generateNonce(1);
        byte[] plaintext = "ChaCha20-Poly1305 fast stream cipher test".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = ShadowsocksCrypto.encrypt(method, subkey, nonce, plaintext);
        byte[] decrypted = ShadowsocksCrypto.decrypt(method, subkey, nonce, ciphertext);
        assertArrayEquals(plaintext, decrypted);
    }
}
