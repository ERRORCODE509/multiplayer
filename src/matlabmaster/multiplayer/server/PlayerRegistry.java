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
 * "colonies:<playerId>" -> colonies JSON, "trades:<playerId>" -> visitors' trades at their colonies while they were
 * offline (JSON array, see ServerMarkets), "raids:<playerId>" -> news of the world's raids on their colonies
 * (ServerRaids), "raidOver:<raid id>" -> how a raid ended (ServerRaids). A ConcurrentHashMap: the network threads
 * reserve factions while the game thread may be saving it.
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

    /** A player's character has a new name. */
    public void setName(String playerId, String name) {
        data.put("name:" + playerId, name);
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

    /** A visitor's trade at a player's colony while they were offline, for their game when they're back. */
    public void queueTrade(String playerId, JSONObject trade) {
        queue("trades", playerId, trade);
    }

    /** The trades queued for a player (MarketSync trades), now theirs: removed from the queue. */
    public JSONArray takeTrades(String playerId) {
        return take("trades", playerId);
    }

    /**
     * Something for a player's game while they're offline (kind: "trades", "raids"...), kept until they're back:
     * "<kind>:<playerId>" -> JSON array.
     */
    public synchronized void queue(String kind, String playerId, JSONObject item) {
        JSONArray items = queued(kind, playerId);
        items.put(item);
        data.put(kind + ":" + playerId, items.toString());
    }

    /** What was queued for a player, now theirs: removed from the queue. */
    public synchronized JSONArray take(String kind, String playerId) {
        JSONArray items = queued(kind, playerId);
        data.remove(kind + ":" + playerId);
        return items;
    }

    private JSONArray queued(String kind, String playerId) {
        String items = data.get(kind + ":" + playerId);
        if (items == null) return new JSONArray();
        try {
            return new JSONArray(items);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    /** A plain value of this world's (e.g. "raidOver:<raid id>"), kept in its save; null if there's none. */
    public String get(String key) {
        return data.get(key);
    }

    public void put(String key, String value) {
        data.put(key, value);
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
