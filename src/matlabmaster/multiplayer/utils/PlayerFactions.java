package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Every player's faction, as the other games see it. Each game's own "player" faction is its own player, so another
 * player's fleet can't be in it: the server gives each connected player one of the mod's player factions
 * (data/world/factions/mp_player_N.faction), and that player's fleet is in it on the server and in every other game.
 * Its relations are that player's reputation, sent by their own game (the only authority on it), so NPC fleets
 * treat each player as their own save says. Named "Unaligned", or after the player's own faction once they've set
 * one up (named it and picked a flag).
 */
public class PlayerFactions {
    public static final String SLOT_PREFIX = "mp_player_";
    /** How many mp_player_N factions the mod defines: the most players connected at once with their own faction. */
    public static final int SLOT_COUNT = 32;
    /** Every faction's relations change, at most this often (seconds). */
    public static final float RELATIONS_INTERVAL = 1f;
    public static final String UNALIGNED = "Unaligned";

    public static boolean isSlot(String factionId) {
        return factionId != null && factionId.startsWith(SLOT_PREFIX);
    }

    public static String slotId(int index) {
        return SLOT_PREFIX + index;
    }

    /** This game's own player faction as other players should see it, or null if it hasn't been set up (unaligned). */
    public static JSONObject describeOwnFaction() throws JSONException {
        FactionAPI own = Global.getSector().getPlayerFaction();
        if (own.getDisplayNameOverride() == null) return null;
        JSONObject info = new JSONObject();
        info.put("name", own.getDisplayNameOverride());
        info.putOpt("nameWithArticle", own.getDisplayNameWithArticleOverride());
        info.putOpt("isOrAre", own.getDisplayIsOrAreOverride());
        info.putOpt("shipPrefix", own.getShipNamePrefixOverride());
        info.putOpt("aOrAn", own.getPersonNamePrefixAOrAnOverride());
        info.putOpt("logo", own.getFactionLogoOverride());
        info.putOpt("crest", own.getFactionCrestOverride());
        return info;
    }

    /** Shows a player faction as describeOwnFaction() described it, or as unaligned (info null). */
    public static void applyLook(String slotId, JSONObject info) {
        FactionAPI slot = Global.getSector().getFaction(slotId);
        if (slot == null) return;
        boolean custom = info != null;
        slot.setDisplayNameOverride(custom ? info.optString("name", UNALIGNED) : UNALIGNED);
        slot.setDisplayNameWithArticleOverride(custom ? info.optString("nameWithArticle", info.optString("name", UNALIGNED)) : "an unaligned captain");
        slot.setDisplayIsOrAreOverride(custom ? info.optString("isOrAre", "is") : "is");
        slot.setShipNamePrefixOverride(custom ? info.optString("shipPrefix", null) : null);
        slot.setPersonNamePrefixAOrAnOverride(custom ? info.optString("aOrAn", null) : null);
        slot.setFactionLogoOverride(custom ? info.optString("logo", null) : null);
        slot.setFactionCrestOverride(custom ? info.optString("crest", null) : null);
    }

    /**
     * Lists a player faction in the intel tab (Factions) while its player is connected, so the other players can
     * look up their faction and reputation; hidden otherwise, like the spare ones.
     */
    public static void setShown(String slotId, boolean shown) {
        FactionAPI slot = Global.getSector().getFaction(slotId);
        if (slot != null && slot.isShowInIntelTab() != shown) slot.setShowInIntelTab(shown);
    }

    /**
     * Hides every player faction: when a game loads (whether it's shown is saved with the faction, and a save made
     * while connected would list players who aren't there) and when it stops being connected.
     */
    public static void hideAll() {
        for (int i = 1; i <= SLOT_COUNT; i++) setShown(slotId(i), false);
    }

    /**
     * This game's player's reputation with every faction (not with the player factions: players are neutral to
     * each other for now), as factionId -> relation (-1..1).
     */
    public static JSONObject ownReputation() throws JSONException {
        FactionAPI own = Global.getSector().getPlayerFaction();
        JSONObject reputation = new JSONObject();
        for (FactionAPI faction : Global.getSector().getAllFactions()) {
            String id = faction.getId();
            if (id.equals(Factions.PLAYER) || isSlot(id)) continue;
            reputation.put(id, round(own.getRelationship(id)));
        }
        return reputation;
    }

    /** Gives a player faction a player's reputation (from ownReputation() in their game). */
    public static void applyReputation(String slotId, JSONObject reputation) throws JSONException {
        FactionAPI slot = Global.getSector().getFaction(slotId);
        if (slot == null) return;
        for (Iterator<?> it = reputation.keys(); it.hasNext(); ) {
            String id = (String) it.next();
            if (id.equals(Factions.PLAYER) || isSlot(id) || Global.getSector().getFaction(id) == null) continue;
            slot.setRelationship(id, (float) reputation.getDouble(id));
        }
    }

    /**
     * The world's relations: every pair of factions except this game's own "player" faction (a dedicated server's
     * isn't anyone), as "a|b" -> relation. Includes the player factions, which carry each player's reputation.
     */
    public static JSONObject worldRelations() throws JSONException {
        List<FactionAPI> factions = new ArrayList<>();
        for (FactionAPI faction : Global.getSector().getAllFactions()) {
            if (!faction.getId().equals(Factions.PLAYER)) factions.add(faction);
        }
        JSONObject relations = new JSONObject();
        for (int i = 0; i < factions.size(); i++) {
            for (int j = i + 1; j < factions.size(); j++) {
                FactionAPI a = factions.get(i), b = factions.get(j);
                if (isSlot(a.getId()) && isSlot(b.getId())) continue; //players are neutral to each other for now
                relations.put(a.getId() + "|" + b.getId(), round(a.getRelationship(b.getId())));
            }
        }
        return relations;
    }

    /** Applies worldRelations() from the server, except anything with this game's own player (its own save decides that). */
    public static void applyWorldRelations(JSONObject relations) throws JSONException {
        for (Iterator<?> it = relations.keys(); it.hasNext(); ) {
            String pair = (String) it.next();
            int bar = pair.indexOf('|');
            if (bar < 0) continue;
            String a = pair.substring(0, bar), b = pair.substring(bar + 1);
            if (a.equals(Factions.PLAYER) || b.equals(Factions.PLAYER)) continue;
            FactionAPI faction = Global.getSector().getFaction(a);
            if (faction == null || Global.getSector().getFaction(b) == null) continue; //a faction this game doesn't have
            faction.setRelationship(b, (float) relations.getDouble(pair));
        }
    }

    /** The entries of now that differ from before (both from worldRelations()). */
    public static JSONObject changed(JSONObject before, JSONObject now) throws JSONException {
        JSONObject changes = new JSONObject();
        for (Iterator<?> it = now.keys(); it.hasNext(); ) {
            String key = (String) it.next();
            if (!before.has(key) || before.getDouble(key) != now.getDouble(key)) changes.put(key, now.get(key));
        }
        return changes;
    }

    /**
     * A player's fleet as their own game describes it is in the "player" faction (theirs): rewrites every factionId
     * that says so, at any depth (the fleet, its officers, and diffs of either), to their player faction.
     */
    public static void rewriteOwnFaction(Object json, String slotId) throws JSONException {
        if (json instanceof JSONObject) {
            JSONObject object = (JSONObject) json;
            for (Iterator<?> it = object.keys(); it.hasNext(); ) {
                String key = (String) it.next();
                Object value = object.get(key);
                if (key.equals("factionId") && Factions.PLAYER.equals(value)) {
                    object.put(key, slotId);
                } else if (key.equals("factionId") && value instanceof JSONObject && Factions.PLAYER.equals(((JSONObject) value).opt("value"))) {
                    ((JSONObject) value).put("value", slotId); //a diff: {"action": ..., "value": "player"}
                } else {
                    rewriteOwnFaction(value, slotId);
                }
            }
        } else if (json instanceof JSONArray) {
            JSONArray array = (JSONArray) json;
            for (int i = 0; i < array.length(); i++) rewriteOwnFaction(array.get(i), slotId);
        }
    }

    /** Relations to 3 decimals, so float noise doesn't count as a change. */
    private static double round(float relation) {
        return Math.round(relation * 1000f) / 1000d;
    }
}
