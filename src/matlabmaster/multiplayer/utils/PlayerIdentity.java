package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import matlabmaster.multiplayer.MultiplayerLog;

import java.util.Map;
import java.util.UUID;

/**
 * Who this player is, for good: a random id made the first time this save joins a server and kept in it (the
 * sector's persistent data), so a server knows them again in every session and keeps their faction and colonies
 * for them while they're offline. A copy of the save is the same player.
 */
public class PlayerIdentity {
    private static final String KEY = "multiplayer_playerId";

    public static String id() {
        Map<String, Object> persistent = Global.getSector().getPersistentData();
        Object id = persistent.get(KEY);
        if (!(id instanceof String)) {
            id = UUID.randomUUID().toString();
            persistent.put(KEY, id);
        }
        return (String) id;
    }

    /**
     * The id the server knows us by (its account's, see server Accounts), from its reply to our hello: this save's
     * from now on. A new game made for an existing account, or a save that joined as someone else before, has
     * another one, and our own colonies, raids, ... are told apart from the other players' by it.
     */
    public static void adopt(String playerId) {
        if (playerId == null || playerId.isEmpty()) return;
        Map<String, Object> persistent = Global.getSector().getPersistentData();
        Object before = persistent.get(KEY);
        if (playerId.equals(before)) return;
        persistent.put(KEY, playerId);
        MultiplayerLog.log().info("This game is the server's player " + playerId + " now (it was " + before + ")");
    }

    /** The player's name, for the server's logs. */
    public static String name() {
        try {
            return Global.getSector().getPlayerPerson().getNameString();
        } catch (Exception e) {
            return "?";
        }
    }
}
