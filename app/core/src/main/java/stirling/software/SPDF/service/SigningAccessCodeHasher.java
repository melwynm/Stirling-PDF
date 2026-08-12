package stirling.software.SPDF.service;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class SigningAccessCodeHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String FORMAT = "pbkdf2-sha256";
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private SigningAccessCodeHasher() {}

    public static String hash(String accessCode) {
        if (accessCode == null || accessCode.isBlank()) {
            throw new IllegalArgumentException("Access code is required");
        }
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] derived = derive(accessCode, salt, ITERATIONS);
        try {
            return String.join(
                    "$",
                    FORMAT,
                    String.valueOf(ITERATIONS),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(salt),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(derived));
        } finally {
            Arrays.fill(derived, (byte) 0);
        }
    }

    public static boolean matches(String accessCode, String encodedHash) {
        if (accessCode == null || encodedHash == null) {
            return false;
        }
        String[] parts = encodedHash.split("\\$", -1);
        if (parts.length != 4 || !FORMAT.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations != ITERATIONS) {
                return false;
            }
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            if (salt.length != SALT_LENGTH_BYTES || expected.length != KEY_LENGTH_BITS / 8) {
                return false;
            }
            byte[] actual = derive(accessCode, salt, iterations);
            try {
                return MessageDigest.isEqual(expected, actual);
            } finally {
                Arrays.fill(actual, (byte) 0);
                Arrays.fill(expected, (byte) 0);
                Arrays.fill(salt, (byte) 0);
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String accessCode, byte[] salt, int iterations) {
        char[] secret = accessCode.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(secret, salt, iterations, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to hash signing access code", e);
        } finally {
            spec.clearPassword();
            Arrays.fill(secret, '\0');
        }
    }
}
