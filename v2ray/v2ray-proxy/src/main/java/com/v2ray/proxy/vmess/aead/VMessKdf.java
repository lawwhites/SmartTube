package com.v2ray.proxy.vmess.aead;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * VMess AEAD Key Derivation Function.
 * Implements the recursive HMAC-SHA256 KDF specified in V2Ray/Xray proxy/vmess/aead/kdf.go.
 */
public class VMessKdf {

    public static final String KDF_SALT_AUTH_ID_ENCRYPTION_KEY = "AES Auth ID Encryption";
    public static final String KDF_SALT_AEAD_RESP_HEADER_LEN_KEY = "AEAD Resp Header Len Key";
    public static final String KDF_SALT_AEAD_RESP_HEADER_LEN_IV = "AEAD Resp Header Len IV";
    public static final String KDF_SALT_AEAD_RESP_HEADER_PAYLOAD_KEY = "AEAD Resp Header Key";
    public static final String KDF_SALT_AEAD_RESP_HEADER_PAYLOAD_IV = "AEAD Resp Header IV";
    public static final String KDF_SALT_VMESS_AEAD_KDF = "VMess AEAD KDF";
    public static final String KDF_SALT_VMESS_HEADER_PAYLOAD_AEAD_KEY = "VMess Header AEAD Key";
    public static final String KDF_SALT_VMESS_HEADER_PAYLOAD_AEAD_IV = "VMess Header AEAD Nonce";
    public static final String KDF_SALT_VMESS_HEADER_PAYLOAD_LENGTH_AEAD_KEY = "VMess Header AEAD Key_Length";
    public static final String KDF_SALT_VMESS_HEADER_PAYLOAD_LENGTH_AEAD_IV = "VMess Header AEAD Nonce_Length";

    public interface Hash {
        void update(byte[] input, int offset, int len);

        default void update(byte[] input) {
            update(input, 0, input.length);
        }

        byte[] digest();

        void reset();

        int size();

        int blockSize();
    }

    public static class Sha256Hash implements Hash {
        private final MessageDigest md;

        public Sha256Hash() {
            try {
                this.md = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void update(byte[] input, int offset, int len) {
            md.update(input, offset, len);
        }

        @Override
        public byte[] digest() {
            return md.digest();
        }

        @Override
        public void reset() {
            md.reset();
        }

        @Override
        public int size() {
            return 32;
        }

        @Override
        public int blockSize() {
            return 64;
        }
    }

    public static class GoHmac implements Hash {
        private final Supplier<Hash> hashSupplier;
        private final Hash inner;
        private final Hash outer;
        private final byte[] ipad;
        private final byte[] opad;

        public GoHmac(Supplier<Hash> hashSupplier, byte[] key) {
            this.hashSupplier = hashSupplier;
            this.inner = hashSupplier.get();
            this.outer = hashSupplier.get();

            int blockSize = inner.blockSize();
            byte[] actualKey = key;
            if (actualKey.length > blockSize) {
                Hash keyHasher = hashSupplier.get();
                keyHasher.update(actualKey);
                actualKey = keyHasher.digest();
            }

            this.ipad = new byte[blockSize];
            this.opad = new byte[blockSize];
            Arrays.fill(this.ipad, (byte) 0x36);
            Arrays.fill(this.opad, (byte) 0x5c);

            for (int i = 0; i < actualKey.length; i++) {
                this.ipad[i] ^= actualKey[i];
                this.opad[i] ^= actualKey[i];
            }

            this.inner.update(this.ipad);
        }

        @Override
        public void update(byte[] input, int offset, int len) {
            inner.update(input, offset, len);
        }

        @Override
        public byte[] digest() {
            byte[] inHash = inner.digest();
            outer.reset();
            outer.update(opad);
            outer.update(inHash);
            return outer.digest();
        }

        @Override
        public void reset() {
            inner.reset();
            inner.update(ipad);
        }

        @Override
        public int size() {
            return outer.size();
        }

        @Override
        public int blockSize() {
            return inner.blockSize();
        }
    }

    private static class HmacCreator {
        final HmacCreator parent;
        final byte[] value;

        HmacCreator(HmacCreator parent, byte[] value) {
            this.parent = parent;
            this.value = value;
        }

        Supplier<Hash> createSupplier() {
            if (parent == null) {
                return () -> new GoHmac(Sha256Hash::new, value);
            } else {
                Supplier<Hash> parentSupplier = parent.createSupplier();
                return () -> new GoHmac(parentSupplier, value);
            }
        }
    }

    public static byte[] kdf(byte[] key, Object... path) {
        HmacCreator creator = new HmacCreator(null, KDF_SALT_VMESS_AEAD_KDF.getBytes(StandardCharsets.UTF_8));
        for (Object v : path) {
            byte[] itemBytes;
            if (v instanceof byte[]) {
                itemBytes = (byte[]) v;
            } else {
                itemBytes = v.toString().getBytes(StandardCharsets.UTF_8);
            }
            creator = new HmacCreator(creator, itemBytes);
        }
        Hash hmac = creator.createSupplier().get();
        hmac.update(key);
        return hmac.digest();
    }

    public static byte[] kdf16(byte[] key, Object... path) {
        byte[] full = kdf(key, path);
        return Arrays.copyOf(full, 16);
    }
}
