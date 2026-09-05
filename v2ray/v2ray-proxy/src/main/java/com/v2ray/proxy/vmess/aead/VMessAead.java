package com.v2ray.proxy.vmess.aead;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * VMess AEAD cryptography utilities.
 */
public class VMessAead {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] CMD_KEY_SALT = "c48619fe-8f02-49e0-b9e9-edf763e17e21".getBytes(StandardCharsets.UTF_8);

    public static byte[] cmdKey(UUID uuid) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return cmdKey(bb.array());
    }

    public static byte[] cmdKey(byte[] uuidBytes) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(uuidBytes);
            md5.update(CMD_KEY_SALT);
            return md5.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] createAuthId(byte[] cmdKey, long timeSeconds) {
        byte[] buffer = new byte[16];
        ByteBuffer bb = ByteBuffer.wrap(buffer);
        bb.putLong(timeSeconds);

        byte[] randBytes = new byte[4];
        RANDOM.nextBytes(randBytes);
        bb.put(randBytes);

        CRC32 crc32 = new CRC32();
        crc32.update(buffer, 0, 12);
        bb.putInt((int) crc32.getValue());

        byte[] aesKey = VMessKdf.kdf16(cmdKey, VMessKdf.KDF_SALT_AUTH_ID_ENCRYPTION_KEY);
        return aesEcbEncrypt(aesKey, buffer);
    }

    public static long openAuthId(byte[] cmdKey, byte[] authId) {
        byte[] aesKey = VMessKdf.kdf16(cmdKey, VMessKdf.KDF_SALT_AUTH_ID_ENCRYPTION_KEY);
        byte[] decrypted = aesEcbDecrypt(aesKey, authId);

        ByteBuffer bb = ByteBuffer.wrap(decrypted);
        long time = bb.getLong();
        bb.getInt(); // random
        int expectedCrc = bb.getInt();

        CRC32 crc32 = new CRC32();
        crc32.update(decrypted, 0, 12);
        if ((int) crc32.getValue() != expectedCrc) {
            throw new IllegalArgumentException("AuthID CRC check failed");
        }

        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(time - now) > 120) {
            throw new IllegalArgumentException("AuthID timestamp out of window: " + time + " vs now " + now);
        }

        return time;
    }

    public static byte[] sealHeader(byte[] cmdKey, byte[] headerPayload) {
        long now = System.currentTimeMillis() / 1000;
        byte[] authId = createAuthId(cmdKey, now);

        byte[] nonce = new byte[8];
        RANDOM.nextBytes(nonce);

        byte[] lenBytes = new byte[2];
        lenBytes[0] = (byte) ((headerPayload.length >> 8) & 0xFF);
        lenBytes[1] = (byte) (headerPayload.length & 0xFF);

        byte[] lenKey = VMessKdf.kdf16(cmdKey, VMessKdf.KDF_SALT_VMESS_HEADER_PAYLOAD_LENGTH_AEAD_KEY, authId, nonce);
        byte[] lenNonce = Arrays.copyOf(VMessKdf.kdf(cmdKey, VMessKdf.KDF_SALT_VMESS_HEADER_PAYLOAD_LENGTH_AEAD_IV, authId, nonce), 12);
        byte[] lenEncrypted = aesGcmEncrypt(lenKey, lenNonce, lenBytes, authId);

        byte[] payloadKey = VMessKdf.kdf16(cmdKey, VMessKdf.KDF_SALT_VMESS_HEADER_PAYLOAD_AEAD_KEY, authId, nonce);
        byte[] payloadNonce = Arrays.copyOf(VMessKdf.kdf(cmdKey, VMessKdf.KDF_SALT_VMESS_HEADER_PAYLOAD_AEAD_IV, authId, nonce), 12);
        byte[] payloadEncrypted = aesGcmEncrypt(payloadKey, payloadNonce, headerPayload, authId);

        ByteBuffer out = ByteBuffer.allocate(16 + 18 + 8 + payloadEncrypted.length);
        out.put(authId);
        out.put(lenEncrypted);
        out.put(nonce);
        out.put(payloadEncrypted);
        return out.array();
    }

    public static byte[] openHeader(byte[] cmdKey, byte[] authId, byte[] lenEncrypted, byte[] nonce, byte[] payloadEncrypted) {
        byte[] lenKey = VMessKdf.kdf16(cmdKey, VMessKdf.KDF_SALT_VMESS_HEADER_PAYLOAD_LENGTH_AEAD_KEY, authId, nonce);
        byte[] lenNonce = Arrays.copyOf(VMessKdf.kdf(cmdKey, VMessKdf.KDF_SALT_VMESS_HEADER_PAYLOAD_LENGTH_AEAD_IV, authId, nonce), 12);
        byte[] lenBytes = aesGcmDecrypt(lenKey, lenNonce, lenEncrypted, authId);

        int length = ((lenBytes[0] & 0xFF) << 8) | (lenBytes[1] & 0xFF);
        if (payloadEncrypted.length != length + 16) {
            throw new IllegalArgumentException("Payload length mismatch: expected " + (length + 16) + " got " + payloadEncrypted.length);
        }

        byte[] payloadKey = VMessKdf.kdf16(cmdKey, VMessKdf.KDF_SALT_VMESS_HEADER_PAYLOAD_AEAD_KEY, authId, nonce);
        byte[] payloadNonce = Arrays.copyOf(VMessKdf.kdf(cmdKey, VMessKdf.KDF_SALT_VMESS_HEADER_PAYLOAD_AEAD_IV, authId, nonce), 12);
        return aesGcmDecrypt(payloadKey, payloadNonce, payloadEncrypted, authId);
    }

    public static byte[] sealResponseHeader(byte[] responseBodyKey, byte[] responseBodyIV, byte[] respPayload) {
        byte[] lenBytes = new byte[2];
        lenBytes[0] = (byte) ((respPayload.length >> 8) & 0xFF);
        lenBytes[1] = (byte) (respPayload.length & 0xFF);

        byte[] lenKey = VMessKdf.kdf16(responseBodyKey, VMessKdf.KDF_SALT_AEAD_RESP_HEADER_LEN_KEY);
        byte[] lenNonce = Arrays.copyOf(VMessKdf.kdf(responseBodyIV, VMessKdf.KDF_SALT_AEAD_RESP_HEADER_LEN_IV), 12);
        byte[] lenEncrypted = aesGcmEncrypt(lenKey, lenNonce, lenBytes, null);

        byte[] payloadKey = VMessKdf.kdf16(responseBodyKey, VMessKdf.KDF_SALT_AEAD_RESP_HEADER_PAYLOAD_KEY);
        byte[] payloadNonce = Arrays.copyOf(VMessKdf.kdf(responseBodyIV, VMessKdf.KDF_SALT_AEAD_RESP_HEADER_PAYLOAD_IV), 12);
        byte[] payloadEncrypted = aesGcmEncrypt(payloadKey, payloadNonce, respPayload, null);

        ByteBuffer out = ByteBuffer.allocate(18 + payloadEncrypted.length);
        out.put(lenEncrypted);
        out.put(payloadEncrypted);
        return out.array();
    }

    public static int openResponseHeaderLength(byte[] responseBodyKey, byte[] responseBodyIV, byte[] lenEncrypted) {
        byte[] lenKey = VMessKdf.kdf16(responseBodyKey, VMessKdf.KDF_SALT_AEAD_RESP_HEADER_LEN_KEY);
        byte[] lenNonce = Arrays.copyOf(VMessKdf.kdf(responseBodyIV, VMessKdf.KDF_SALT_AEAD_RESP_HEADER_LEN_IV), 12);
        byte[] lenBytes = aesGcmDecrypt(lenKey, lenNonce, lenEncrypted, null);
        return ((lenBytes[0] & 0xFF) << 8) | (lenBytes[1] & 0xFF);
    }

    public static byte[] openResponseHeaderPayload(byte[] responseBodyKey, byte[] responseBodyIV, byte[] payloadEncrypted) {
        byte[] payloadKey = VMessKdf.kdf16(responseBodyKey, VMessKdf.KDF_SALT_AEAD_RESP_HEADER_PAYLOAD_KEY);
        byte[] payloadNonce = Arrays.copyOf(VMessKdf.kdf(responseBodyIV, VMessKdf.KDF_SALT_AEAD_RESP_HEADER_PAYLOAD_IV), 12);
        return aesGcmDecrypt(payloadKey, payloadNonce, payloadEncrypted, null);
    }

    public static byte[] aesEcbEncrypt(byte[] key, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] aesEcbDecrypt(byte[] key, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] aesGcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), spec);
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), spec);
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
