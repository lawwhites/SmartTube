package com.v2ray.proxy.shadowsocks;

import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Shadowsocks AEAD cryptography utilities according to SIP008 specification.
 */
public class ShadowsocksCrypto {

    public static final String METHOD_AES_128_GCM = "aes-128-gcm";
    public static final String METHOD_AES_256_GCM = "aes-256-gcm";
    public static final String METHOD_CHACHA20_POLY1305 = "chacha20-poly1305";

    public static final byte[] SS_SUBKEY_INFO = "ss-subkey".getBytes(StandardCharsets.UTF_8);

    public static int getKeySize(String method) {
        String m = method.toLowerCase().replace('_', '-');
        if (METHOD_AES_128_GCM.equals(m)) {
            return 16;
        } else if (METHOD_AES_256_GCM.equals(m) || METHOD_CHACHA20_POLY1305.equals(m) || "chacha20-ietf-poly1305".equals(m)) {
            return 32;
        }
        throw new IllegalArgumentException("Unsupported Shadowsocks method: " + method);
    }

    public static int getSaltSize(String method) {
        return getKeySize(method);
    }

    public static byte[] passwordToKey(String password, int keySize) {
        try {
            byte[] pwdBytes = password.getBytes(StandardCharsets.UTF_8);
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] key = new byte[keySize];
            byte[] d = new byte[0];
            int offset = 0;
            while (offset < keySize) {
                md5.reset();
                if (d.length > 0) {
                    md5.update(d);
                }
                md5.update(pwdBytes);
                d = md5.digest();
                int copyLen = Math.min(d.length, keySize - offset);
                System.arraycopy(d, 0, key, offset, copyLen);
                offset += copyLen;
            }
            return key;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] deriveSubkey(byte[] masterKey, byte[] salt, int subkeyLen) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA1Digest());
        hkdf.init(new HKDFParameters(masterKey, salt, SS_SUBKEY_INFO));
        byte[] subkey = new byte[subkeyLen];
        hkdf.generateBytes(subkey, 0, subkeyLen);
        return subkey;
    }

    public static byte[] generateNonce(long counter) {
        byte[] nonce = new byte[12];
        for (int i = 0; i < 8; i++) {
            nonce[i] = (byte) ((counter >> (i * 8)) & 0xFF);
        }
        return nonce;
    }

    public static Cipher createCipher(String method) {
        try {
            String m = method.toLowerCase().replace('_', '-');
            if (METHOD_AES_128_GCM.equals(m) || METHOD_AES_256_GCM.equals(m)) {
                return Cipher.getInstance("AES/GCM/NoPadding");
            } else if (METHOD_CHACHA20_POLY1305.equals(m) || "chacha20-ietf-poly1305".equals(m)) {
                return Cipher.getInstance("ChaCha20-Poly1305");
            }
            throw new IllegalArgumentException("Unsupported method: " + method);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static java.security.spec.AlgorithmParameterSpec createParamSpec(String method, byte[] nonce) {
        String m = method.toLowerCase().replace('_', '-');
        if (METHOD_AES_128_GCM.equals(m) || METHOD_AES_256_GCM.equals(m)) {
            return new GCMParameterSpec(128, nonce);
        } else {
            return new IvParameterSpec(nonce);
        }
    }

    public static SecretKeySpec createKeySpec(String method, byte[] subkey) {
        String m = method.toLowerCase().replace('_', '-');
        if (METHOD_AES_128_GCM.equals(m) || METHOD_AES_256_GCM.equals(m)) {
            return new SecretKeySpec(subkey, "AES");
        } else {
            return new SecretKeySpec(subkey, "ChaCha20");
        }
    }

    public static byte[] encrypt(String method, byte[] subkey, byte[] nonce, byte[] plaintext) {
        try {
            Cipher cipher = createCipher(method);
            cipher.init(Cipher.ENCRYPT_MODE, createKeySpec(method, subkey), createParamSpec(method, nonce));
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] decrypt(String method, byte[] subkey, byte[] nonce, byte[] ciphertext) {
        try {
            Cipher cipher = createCipher(method);
            cipher.init(Cipher.DECRYPT_MODE, createKeySpec(method, subkey), createParamSpec(method, nonce));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
