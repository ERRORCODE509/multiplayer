package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;

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

    /** The player's name, for the server's logs. */
    public static String name() {
        try {
            return Global.getSector().getPlayerPerson().getNameString();
        } catch (Exception e) {
            return "?";
        }
    }
}
