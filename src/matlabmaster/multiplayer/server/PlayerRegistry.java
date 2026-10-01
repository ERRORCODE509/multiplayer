package matlabmaster.multiplayer.server;

import com.fs.starfarer.api.Global;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.utils.PlayerFactions;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every player who has ever joined this world, kept in its save (the sector's persistent data), so their faction
 * and colonies stay in the world while they're offline: a player's faction is theirs for good (reserved by their
 * permanent player id, see PlayerIdentity), with its look, and the colonies their game last described.
 *
 * Stored as a plain String -> String map, so the save never depends on the mod's classes:
 * "faction:<playerId>" -> mp_player_N, "name:<playerId>" -> their name, "look:<mp_player_N>" -> look JSON,
 * "colonies:<playerId>" -> colonies JSON. A ConcurrentHashMap: the network threads reserve factions while the
 * game thread may be saving it.
 */
public class PlayerRegistry {
    private static final String KEY = "multiplayer_players";
    private Map<String, String> data = new ConcurrentHashMap<>();

    /** This game's registry, from its save (an empty one in a world nobody has joined yet). */
    @SuppressWarnings("unchecked")
    public void load() {
        Map<String, Object> persistent = Global.getSector().getPersistentData();
        Object stored = persistent.get(KEY);
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        if (stored instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) stored).entrySet()) {
                if (e.getKey() instanceof String && e.getValue() instanceof String) map.put((String) e.getKey(), (String) e.getValue());
            }
        }
        persistent.put(KEY, map); //saved with the game from now on
        data = map;
    }

    /**
     * The player's faction: the one reserved for them, or the first nobody has, now theirs for good. Null if every
     * player faction is someone's.
     */
    public synchronized String reserveFaction(String playerId, String name) {
        data.put("name:" + playerId, name);
        String faction = data.get("faction:" + playerId);
        if (faction != null) return faction;
        for (int i = 1; i <= PlayerFactions.SLOT_COUNT; i++) {
            String slot = PlayerFactions.slotId(i);
            if (!data.containsValue(slot)) {
                data.put("faction:" + playerId, slot);
                MultiplayerLog.log().info("New player " + name + " (" + playerId + "): their faction is " + slot);
                return slot;
            }
        }
        return null;
    }

    public String faction(String playerId) {
        return data.get("faction:" + playerId);
    }

    /** Every reserved player faction -> its player's id. */
    public Map<String, String> factions() {
        Map<String, String> factions = new ConcurrentHashMap<>();
        for (Map.Entry<String, String> e : data.entrySet()) {
            if (e.getKey().startsWith("faction:")) factions.put(e.getValue(), e.getKey().substring("faction:".length()));
        }
        return factions;
    }

    /** How a player faction looks (PlayerFactions.describeOwnFaction()), or null if unaligned. */
    public JSONObject look(String faction) {
        String look = data.get("look:" + faction);
        if (look == null) return null;
        try {
            return new JSONObject(look);
        } catch (JSONException e) {
            return null;
        }
    }

    public void setLook(String faction, JSONObject look) {
        if (look == null) data.remove("look:" + faction);
        else data.put("look:" + faction, look.toString());
    }

    /** A player's colonies as their game last described them (ColonyMirrors.describeOwnColonies()). */
    public JSONArray colonies(String playerId) {
        String colonies = data.get("colonies:" + playerId);
        if (colonies == null) return new JSONArray();
        try {
            return new JSONArray(colonies);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    public void setColonies(String playerId, JSONArray colonies) {
        data.put("colonies:" + playerId, colonies.toString());
    }

    /** Every player who has described colonies. */
    public List<String> playersWithColonies() {
        List<String> players = new ArrayList<>();
        for (String key : data.keySet()) {
            if (key.startsWith("colonies:")) players.add(key.substring("colonies:".length()));
        }
        return players;
    }
}
