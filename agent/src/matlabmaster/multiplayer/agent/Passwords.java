package matlabmaster.multiplayer.agent;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Password hashing for the server's accounts (the mod's), for the same reason as SecureSockets: PBKDF2 with
 * HMAC-SHA256, a random salt per account. Through System.getProperties(): HASH_KEY, a BiFunction<String salt,
 * String password, String hash>; SALT_KEY, a Supplier<String> of new salts. Both Base64.
 */
public class Passwords {
    public static final String HASH_KEY = "multiplayer.hashPassword";
    public static final String SALT_KEY = "multiplayer.newSalt";
    private static final int ITERATIONS = 210000;
    private static final int BITS = 256;

    static void install() {
        System.getProperties().put(HASH_KEY, (BiFunction<String, String, String>) Passwords::hash);
        System.getProperties().put(SALT_KEY, (Supplier<String>) Passwords::newSalt);
    }

    static String hash(String salt, String password) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, BITS);
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            spec.clearPassword();
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't hash a password: " + e, e);
        }
    }

    static String newSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }
}
