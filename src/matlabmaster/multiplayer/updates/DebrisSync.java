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

/**
 * The debris field a battle leaves (vanilla's CoreScript makes it, in the game the battle happened in: a player's):
 * their game sends it, and the server and every other game get the same field, so anyone can salvage it. When one
 * is gone from a game (salvaged, or it ran out), that game says so and it goes everywhere. A field is known by the
 * id it had in the game it came from, kept in its memory (an entity's id can't be relied on once it's been added).
 *
 * Field: {"location", "x", "y", "band", "density", "baseDensity", "glowsDays", "lastsDays", "salvageXP", ...}.
 */
public class DebrisSync {
    private static final String ID = "$mp_debrisId";

    /** The id this game knows a field by: the one it was given here, or its own if it was made here. */
    public static String idOf(SectorEntityToken field) {
        String id = field.getMemoryWithoutUpdate().getString(ID);
        if (id == null) {
            id = field.getId();
            field.getMemoryWithoutUpdate().set(ID, id);
        }
        return id;
    }

    /** The battle debris fields in a location, by id. */
    public static JSONObject battleFields(LocationAPI location) throws JSONException {
        JSONObject fields = new JSONObject();
        for (CampaignTerrainAPI terrain : location.getTerrainCopy()) {
            if (!(terrain.getPlugin() instanceof DebrisFieldTerrainPlugin)) continue;
            DebrisFieldParams p = ((DebrisFieldTerrainPlugin) terrain.getPlugin()).params;
            if (p == null || p.source != DebrisFieldSource.BATTLE) continue;
            JSONObject field = new JSONObject();
            field.put("location", location.getId());
            field.put("x", terrain.getLocation().x);
            field.put("y", terrain.getLocation().y);
            field.put("band", p.bandWidthInEngine);
            field.put("density", p.density);
            field.put("baseDensity", p.baseDensity);
            field.put("glowsDays", p.glowsDays);
            field.put("lastsDays", p.lastsDays);
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
            DebrisFieldParams p = ((DebrisFieldTerrainPlugin) existing.getPlugin()).params;
            p.density = (float) field.getDouble("density");
            p.lastsDays = (float) field.getDouble("lastsDays");
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
