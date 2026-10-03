package matlabmaster.multiplayer.server;

import matlabmaster.multiplayer.MultiplayerLog;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The server's accounts: a username and password decide who a player is, not the player id their save carries
 * (anyone can copy a save). Kept in the world's save (PlayerRegistry): "account:<username, lower case>" ->
 * {"player", "salt", "hash", "name"}, and "accountOf:<player id>" -> username, so a player id is one account's.
 * The first login with a new username makes the account, bound to the player id of the save it came with. The
 * passwords are hashed by the multiplayer agent (PBKDF2, see agent Passwords: the game forbids mods the crypto);
 * without the agent there are no accounts, and players are known by their saves' ids as before.
 */
public class Accounts {
    private static final String HASH_KEY = "multiplayer.hashPassword";
    private static final String SALT_KEY = "multiplayer.newSalt";
    private static final int MIN_PASSWORD = 6;

    /** Why a login was refused, for the player. */
    public static class Refused extends Exception {
        Refused(String reason) {
            super(reason);
        }
    }

    public static boolean available() {
        return System.getProperties().get(HASH_KEY) instanceof BiFunction && System.getProperties().get(SALT_KEY) instanceof Supplier;
    }

    /** The player id of this account (made if it's new), or Refused. Network thread (the hello). */
    @SuppressWarnings("unchecked")
    static synchronized String login(PlayerRegistry registry, String user, String password, String savePlayerId, String name) throws Refused, JSONException {
        if (user == null || !user.matches("[A-Za-z0-9 _.\\-]{1,32}")) throw new Refused("Pick a username: up to 32 letters, digits, spaces, '_', '.' or '-'");
        if (password == null || password.isEmpty()) throw new Refused("Enter your password");
        BiFunction<String, String, String> hash = (BiFunction<String, String, String>) System.getProperties().get(HASH_KEY);
        String key = "account:" + user.trim().toLowerCase();
        String stored = registry.get(key);
        if (stored != null) {
            JSONObject account = new JSONObject(stored);
            if (!hash.apply(account.getString("salt"), password).equals(account.getString("hash"))) throw new Refused("Wrong password for " + user);
            return account.getString("player");
        }
        if (password.length() < MIN_PASSWORD) throw new Refused("New account: pick a password of at least " + MIN_PASSWORD + " characters");
        String taken = registry.get("accountOf:" + savePlayerId);
        if (taken != null) throw new Refused("This save already belongs to the account " + taken + ": log in with it");
        String salt = ((Supplier<String>) System.getProperties().get(SALT_KEY)).get();
        JSONObject account = new JSONObject().put("player", savePlayerId).put("salt", salt).put("hash", hash.apply(salt, password)).put("name", user.trim());
        registry.put(key, account.toString());
        registry.put("accountOf:" + savePlayerId, user.trim());
        MultiplayerLog.log().info("New account " + user.trim() + " for " + name + " (" + savePlayerId + ")");
        return savePlayerId;
    }
}
