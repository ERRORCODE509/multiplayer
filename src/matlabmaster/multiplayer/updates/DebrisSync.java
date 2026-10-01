package matlabmaster.multiplayer.updates;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldSource;
import com.fs.starfarer.api.util.Misc;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The debris field a battle leaves (vanilla's CoreScript makes it, in the game the battle happened in: a player's):
 * their game sends it, and the server and every other game get the same field, so anyone can salvage it. When one
 * is gone from a game (salvaged, or it ran out), that game says so and it goes everywhere. A field is known by the
 * id it had in the game it came from, kept in its memory (an entity's id can't be relied on once it's been added).
 *
 * Field: {"location", "x", "y", "band", "density", "baseDensity", "glowsDays", "lastsDays", "salvageXP", ...}, the
 * days counted from now (a field sent to a player who joins later has been there a while).
 */
public class DebrisSync {
    private static final String ID = "$mp_debrisId";
    private static final String SHARED = "$mp_debrisShared";

    /** The id this game knows a field by: the one it was given here, or its own if it was made here. */
    public static String idOf(SectorEntityToken field) {
        String id = field.getMemoryWithoutUpdate().getString(ID);
        if (id == null) {
            id = field.getId();
            field.getMemoryWithoutUpdate().set(ID, id);
        }
        return id;
    }

    /**
     * Whether a field was made in this game (a battle here) and not given to a server yet. Once given, it's the
     * world's, like the ones received: on joining again it may have been salvaged meanwhile.
     */
    private static boolean madeHere(SectorEntityToken field) {
        if (field.getMemoryWithoutUpdate().getBoolean(SHARED)) return false;
        String id = field.getMemoryWithoutUpdate().getString(ID);
        return id == null || id.equals(field.getId());
    }

    /** A field made here was given to the server (in this save: see madeHere). */
    public static void shared(String id) {
        CampaignTerrainAPI field = find(id);
        if (field != null) field.getMemoryWithoutUpdate().set(SHARED, true);
    }

    /** Every battle debris field in this game, by id. */
    public static JSONObject allBattleFields() throws JSONException {
        JSONObject fields = new JSONObject();
        for (LocationAPI location : Global.getSector().getAllLocations()) {
            JSONObject here = battleFields(location, false);
            for (Iterator<?> it = here.keys(); it.hasNext(); ) {
                String id = (String) it.next();
                fields.put(id, here.get(id));
            }
        }
        return fields;
    }

    /**
     * The battle debris fields in a location, by id; only those made in this game if madeHereOnly (a player's game
     * sends only its own: the ones it got from a server, maybe saved from an earlier session, are the server's to say).
     */
    public static JSONObject battleFields(LocationAPI location, boolean madeHereOnly) throws JSONException {
        JSONObject fields = new JSONObject();
        for (CampaignTerrainAPI terrain : location.getTerrainCopy()) {
            if (!(terrain.getPlugin() instanceof DebrisFieldTerrainPlugin)) continue;
            DebrisFieldTerrainPlugin plugin = (DebrisFieldTerrainPlugin) terrain.getPlugin();
            DebrisFieldParams p = plugin.params;
            if (p == null || p.source != DebrisFieldSource.BATTLE) continue;
            if (madeHereOnly && !madeHere(terrain)) continue;
            JSONObject field = new JSONObject();
            field.put("location", location.getId());
            field.put("x", terrain.getLocation().x);
            field.put("y", terrain.getLocation().y);
            field.put("band", p.bandWidthInEngine);
            field.put("density", p.density);
            field.put("baseDensity", p.baseDensity);
            field.put("glowsDays", Math.max(0f, plugin.getGlowDaysLeft()));
            field.put("lastsDays", plugin.getDaysLeft());
            field.put("salvageXP", p.baseSalvageXP);
            field.put("minSize", p.minSize);
            field.put("maxSize", p.maxSize);
            fields.put(idOf(terrain), field);
        }
        return fields;
    }

    /** Adds a field from another game (or updates it, a later battle having added to it). */
    public static void apply(String id, JSONObject field) throws JSONException {
        CampaignTerrainAPI existing = find(id);
        if (existing != null) {
            DebrisFieldTerrainPlugin plugin = (DebrisFieldTerrainPlugin) existing.getPlugin();
            DebrisFieldParams p = plugin.params;
            p.density = (float) field.getDouble("density");
            p.lastsDays += (float) field.getDouble("lastsDays") - plugin.getDaysLeft(); //its age here stays as it is
            p.baseSalvageXP = field.getLong("salvageXP");
            return;
        }
        String locationId = field.getString("location");
        LocationAPI location = locationId.equals(Global.getSector().getHyperspace().getId())
                ? Global.getSector().getHyperspace() : Global.getSector().getStarSystem(locationId);
        if (location == null) return;
        DebrisFieldParams p = new DebrisFieldParams((float) field.getDouble("band"), (float) field.getDouble("density"),
                (float) field.getDouble("lastsDays"), (float) field.getDouble("glowsDays"));
        p.baseDensity = (float) field.getDouble("baseDensity");
        p.baseSalvageXP = field.getLong("salvageXP");
        p.minSize = (float) field.optDouble("minSize", p.minSize);
        p.maxSize = (float) field.optDouble("maxSize", p.maxSize);
        p.source = DebrisFieldSource.BATTLE;
        SectorEntityToken debris = Misc.addDebrisField(location, p, null);
        debris.setLocation((float) field.getDouble("x"), (float) field.getDouble("y"));
        debris.getMemoryWithoutUpdate().set(ID, id);
    }

    /** A field gone in another game: gone here too. */
    public static void remove(String id) {
        CampaignTerrainAPI field = find(id);
        if (field != null && field.getContainingLocation() != null) field.getContainingLocation().removeEntity(field);
    }

    /**
     * Every field received from another game that isn't one of these (the world's, on joining it): salvaged or run
     * out while this game was away, or from another server's world (they're in this save). Returns how many went.
     */
    public static int removeReceivedExcept(Set<String> keep) {
        List<CampaignTerrainAPI> gone = new ArrayList<>();
        for (LocationAPI location : Global.getSector().getAllLocations()) {
            for (CampaignTerrainAPI terrain : location.getTerrainCopy()) {
                if (!(terrain.getPlugin() instanceof DebrisFieldTerrainPlugin) || madeHere(terrain)) continue;
                if (!keep.contains(terrain.getMemoryWithoutUpdate().getString(ID))) gone.add(terrain);
            }
        }
        for (CampaignTerrainAPI terrain : gone) terrain.getContainingLocation().removeEntity(terrain);
        return gone.size();
    }

    public static boolean exists(String id) {
        return find(id) != null;
    }

    private static CampaignTerrainAPI find(String id) {
        for (LocationAPI location : Global.getSector().getAllLocations()) {
            for (CampaignTerrainAPI terrain : location.getTerrainCopy()) {
                if (terrain.getPlugin() instanceof DebrisFieldTerrainPlugin && id.equals(terrain.getMemoryWithoutUpdate().getString(ID))) {
                    return terrain;
                }
            }
        }
        return null;
    }
}
