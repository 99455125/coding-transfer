package dev.codingtransfer.core;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/** Authenticated outer clipboard envelope; inner VMSYNC and file protocols remain unchanged. */
public final class EncryptedFrameCodec {
    private static final byte[] DOMAIN = "every-sync/encrypted-frame\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] INNER_VERSION = new byte[]{0x45, 0x53, 0x59, 0x4e, 1};
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int MAX_PLAINTEXT = TransferLimits.MAX_PACKET_CHARS + INNER_VERSION.length;
    private static final Pattern IDENTITY = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private EncryptedFrameCodec() {}

    public record Decoded(byte[] plaintext, byte[] nonce) {
        public Decoded {
            plaintext = plaintext.clone();
            nonce = nonce.clone();
        }
        @Override public byte[] plaintext() { return plaintext.clone(); }
        @Override public byte[] nonce() { return nonce.clone(); }
    }

    public static String encode(String identity, byte[] key, byte[] plaintext) throws IOException {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return encode(identity, key, plaintext, nonce);
    }

    static String encode(String identity, byte[] key, byte[] plaintext, byte[] nonce) throws IOException {
        validateIdentity(identity);
        validateKey(key);
        if (plaintext == null || plaintext.length > MAX_PLAINTEXT) throw new IOException("加密帧过大");
        if (nonce == null || nonce.length != NONCE_BYTES) throw new IOException("AES-GCM nonce 必须为 12 字节");
        byte[] versioned = new byte[INNER_VERSION.length + plaintext.length];
        System.arraycopy(INNER_VERSION, 0, versioned, 0, INNER_VERSION.length);
        System.arraycopy(plaintext, 0, versioned, INNER_VERSION.length, plaintext.length);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, identity, key, nonce, versioned);
        byte[] packed = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, packed, 0, nonce.length);
        System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
        return identity + " " + Base64.getEncoder().encodeToString(packed);
    }

    public static Decoded decode(String expectedIdentity, byte[] key, String frame) throws IOException {
        validateIdentity(expectedIdentity);
        validateKey(key);
        if (frame == null || frame.length() > maxFrameChars()) throw new IOException("加密帧为空或过大");
        String normalized = frame.strip();
        int separator = normalized.indexOf(' ');
        if (separator < 1 || separator != normalized.lastIndexOf(' ')) throw new IOException("无效加密帧");
        String identity = normalized.substring(0, separator);
        validateIdentity(identity);
        if (!expectedIdentity.equals(identity)) throw new IOException("传输识别串不匹配");
        String encoded = normalized.substring(separator + 1);
        if (encoded.isEmpty() || encoded.length() > maxBase64Chars() || !encoded.matches("[A-Za-z0-9+/]+={0,2}"))
            throw new IOException("无效加密帧数据");
        byte[] packed;
        try { packed = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw new IOException("无效加密帧 Base64", e); }
        if (packed.length < NONCE_BYTES + TAG_BYTES || packed.length > MAX_PLAINTEXT + NONCE_BYTES + TAG_BYTES)
            throw new IOException("加密帧长度无效");
        byte[] nonce = Arrays.copyOfRange(packed, 0, NONCE_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(packed, NONCE_BYTES, packed.length);
        byte[] versioned = crypt(Cipher.DECRYPT_MODE, identity, key, nonce, ciphertext);
        if (versioned.length < INNER_VERSION.length || !Arrays.equals(INNER_VERSION, Arrays.copyOf(versioned, INNER_VERSION.length)))
            throw new IOException("不支持的加密帧版本");
        return new Decoded(Arrays.copyOfRange(versioned, INNER_VERSION.length, versioned.length), nonce);
    }

    private static byte[] crypt(int mode, String identity, byte[] key, byte[] nonce, byte[] input) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(DOMAIN);
            cipher.updateAAD(identity.getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(input);
        } catch (AEADBadTagException e) {
            throw new IOException("加密帧认证失败（密钥错误或内容已损坏）", e);
        } catch (GeneralSecurityException e) {
            throw new IOException("无法处理 AES-GCM 加密帧", e);
        }
    }

    private static void validateIdentity(String identity) throws IOException {
        if (identity == null || !IDENTITY.matcher(identity).matches()) throw new IOException("传输识别串格式无效");
    }

    private static void validateKey(byte[] key) throws IOException {
        if (key == null || key.length != 32) throw new IOException("共享密钥必须为 256 位");
    }

    public static int maxEncodedFrameChars() {
        return (int) Math.min(Integer.MAX_VALUE, 128L + 1 + maxBase64Chars());
    }
    private static int maxFrameChars() { return maxEncodedFrameChars(); }
    private static int maxBase64Chars() { return (int) Math.min(Integer.MAX_VALUE, ((long) MAX_PLAINTEXT + NONCE_BYTES + TAG_BYTES + 2) / 3 * 4); }
}
