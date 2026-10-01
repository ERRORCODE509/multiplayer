package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * The memory flags of a world NPC fleet that decide how it treats the players, sent with it so every game's copy
 * decides the same (CopyAI asks vanilla, which reads them): who it was made hostile to, and how much attacking it
 * costs in reputation. A raid on a player's colony is made hostile to their faction ($makeHostile_mp_player_N in
 * the world): in their game that's the player ($makeHostile), in the others it's someone else's faction.
 *
 * Plain $makeHostile in the world is about the server game's own player (the host, or nobody on a dedicated
 * server), so it's never sent: the world's raids on players' colonies are made hostile to their faction instead.
 */
public class FleetFlags {
    private static final String HOSTILE_TO = MemFlags.MEMORY_KEY_MAKE_HOSTILE + "_";
    private static final String[] REP_IMPACT = {MemFlags.MEMORY_KEY_LOW_REP_IMPACT, MemFlags.MEMORY_KEY_NO_REP_IMPACT};

    /** This game's player's faction in the others (mp_player_N), while connected; null otherwise. */
    public static volatile String ownFaction;

    /** World side: the flags of an NPC fleet, flag -> true (an empty object for most). */
    public static JSONObject describe(CampaignFleetAPI fleet) throws JSONException {
        JSONObject flags = new JSONObject();
        MemoryAPI memory = fleet.getMemoryWithoutUpdate();
        for (String key : memory.getKeys()) {
            if (!key.startsWith(HOSTILE_TO) || !memory.getBoolean(key)) continue;
            //a faction's (not one of $makeHostile's reasons, which are named the same way, see Misc.setFlagWithReason)
            String faction = key.substring(HOSTILE_TO.length());
            if (!faction.equals(Factions.PLAYER) && Global.getSector().getFaction(faction) != null) flags.put(key, true);
        }
        for (String key : REP_IMPACT) {
            if (memory.getBoolean(key)) flags.put(key, true);
        }
        return flags;
    }

    /** A copy: the flags the world's fleet has now (all of them, as describe() gives them). */
    public static void apply(CampaignFleetAPI copy, JSONObject flags) {
        MemoryAPI memory = copy.getMemoryWithoutUpdate();
        for (String key : memory.getKeys().toArray(new String[0])) {
            if (key.startsWith(HOSTILE_TO) || key.equals(MemFlags.MEMORY_KEY_MAKE_HOSTILE)) memory.unset(key);
        }
        for (String key : REP_IMPACT) memory.unset(key);
        for (Iterator<?> it = flags.keys(); it.hasNext(); ) set(memory, (String) it.next(), true);
    }

    /** A copy: one flag changed (a diff's ADDED, UPDATE or REMOVED). */
    public static void set(MemoryAPI memory, String key, boolean value) {
        String own = ownFaction;
        if (own != null && key.equals(HOSTILE_TO + own)) key = MemFlags.MEMORY_KEY_MAKE_HOSTILE; //made hostile to us
        if (value) memory.set(key, true);
        else memory.unset(key);
    }
}
