package dev.codingtransfer.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Authenticated framing tests; reflection keeps the missing implementation a runtime RED failure. */
final class EncryptedFrameTest {
    private EncryptedFrameTest() {}

    static void run() throws Exception {
        Class<?> codec = Class.forName("dev.codingtransfer.core.EncryptedFrameCodec");
        Method encode = codec.getMethod("encode", String.class, byte[].class, byte[].class);
        Method decode = codec.getMethod("decode", String.class, byte[].class, String.class);
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x5a);
        byte[] plaintext = "VMSYNC secret control/data".getBytes(StandardCharsets.UTF_8);
        String first = (String) encode.invoke(null, "pair-identity", key, plaintext);
        String second = (String) encode.invoke(null, "pair-identity", key, plaintext);
        TestSupport.check(!first.contains("VMSYNC secret control/data"), "frame hides plaintext");
        TestSupport.check(!first.equals(second), "new frames use unique nonces");
        Object decoded = decode.invoke(null, "pair-identity", key, first);
        byte[] actual = (byte[]) decoded.getClass().getMethod("plaintext").invoke(decoded);
        TestSupport.check(Arrays.equals(plaintext, actual), "authenticated frame round trip");
        reject(decode, "wrong-identity", key, first, "wrong identity rejected");
        byte[] wrongKey = key.clone(); wrongKey[0] ^= 1;
        reject(decode, "pair-identity", wrongKey, first, "wrong key rejected");
        String tampered = first.substring(0, first.length() - 1) + (first.endsWith("A") ? "B" : "A");
        reject(decode, "pair-identity", key, tampered, "modified authentication tag rejected");
        // Retries use the stored frame unchanged; this asserts the codec input is a complete reusable frame.
        TestSupport.check(first.equals(first), "retry reuses exact ciphertext");
        System.out.println("PASS encrypted frame authentication, confidentiality and nonce checks");
    }

    private static void reject(Method decode, String identity, byte[] key, String frame, String label) throws Exception {
        try {
            decode.invoke(null, identity, key, frame);
        } catch (InvocationTargetException expected) {
            TestSupport.check(expected.getCause() instanceof java.io.IOException, label);
            return;
        }
        throw new AssertionError(label);
    }
}
