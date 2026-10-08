package io.dataease.enterprise.identity.manage;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Fixed, bounded parameters; persisted hashes never enter API responses. */
public final class PasswordCodec {
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public String encode(char[] password) {
        validate(password);
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "PBKDF2_SHA256$" + ITERATIONS + "$" + ENCODER.encodeToString(salt) + "$" + ENCODER.encodeToString(derive(password, salt));
    }

    public boolean matches(char[] password, String encoded) {
        validate(password);
        verifyEncoding(encoded);
        String[] parts = encoded == null ? new String[0] : encoded.split("\\$", -1);
        byte[] salt = decode(parts[2],16), expected = decode(parts[3],32);
        byte[] actual = derive(password,salt);
        try { return MessageDigest.isEqual(expected,actual); }
        finally { Arrays.fill(actual,(byte)0); }
    }

    public static void verifyEncoding(String encoded) {
        String[] parts = encoded == null ? new String[0] : encoded.split("\\$", -1);
        if (parts.length != 4 || !parts[0].equals("PBKDF2_SHA256") || !parts[1].equals(Integer.toString(ITERATIONS))) {
            throw new IllegalStateException("Unsupported management credential parameters");
        }
        decode(parts[2],16); decode(parts[3],32);
    }

    private static byte[] decode(String value, int size) {
        try {
            byte[] decoded = DECODER.decode(value);
            if (decoded.length == size && ENCODER.encodeToString(decoded).equals(value)) return decoded;
        } catch (IllegalArgumentException ignored) { /* Reject malformed stored parameters. */ }
        throw new IllegalStateException("Invalid management credential encoding");
    }

    public static void validate(char[] password) {
        if (password == null || password.length < 12 || password.length > 128) throw new IllegalArgumentException("Password length must be 12 to 128");
        for (char c : password) if (Character.isISOControl(c) || Character.isSurrogate(c)) throw new IllegalArgumentException("Invalid password character");
    }

    private static byte[] derive(char[] password, byte[] salt) {
        var specification = new PBEKeySpec(password,salt,ITERATIONS,256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded(); }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Required password algorithm unavailable",e); }
        finally { specification.clearPassword(); }
    }
}
