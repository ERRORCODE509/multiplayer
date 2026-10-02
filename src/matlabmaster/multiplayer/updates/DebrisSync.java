package matlabmaster.multiplayer.updates;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.ids.Drops;
import com.fs.starfarer.api.impl.campaign.procgen.SalvageEntityGenDataSpec;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldSource;
import com.fs.starfarer.api.util.Misc;
import org.json.JSONArray;
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
 * Field: {"location", "x", "y", "band", "density", "baseDensity", "glowsDays", "lastsDays", "salvageXP", "basic"
 * (its basic salvage value), "ships" (to recover), ...}, the days counted from now (a field sent to a player who
 * joins later has been there a while). Its random extras (weapons, cargo picks) are each game's own.
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
            //what it holds: its basic salvage (what a battle adds to it) and the ships to recover from it
            field.put("basic", basicValue(terrain));
            field.put("ships", recoverableShips(terrain));
            fields.put(idOf(terrain), field);
        }
        return fields;
    }

    /** Each shared field's contents as last sent or received (id -> signature()), see changed(). */
    private static final java.util.Map<String, String> knownContents = new java.util.HashMap<>();

    /** What a field holds, for changed(): its basic salvage and how many ships can be recovered from it. */
    private static String signature(JSONObject field) {
        JSONArray ships = field.optJSONArray("ships");
        return field.optLong("basic", 0) + ":" + (ships == null ? 0 : ships.length());
    }

    /**
     * Whether a field holds something else than when it was last sent or received: a later battle nearby added to
     * it (vanilla adds its salvage to a field rather than making another), or ships were recovered from it, so the
     * others' copies need it again. Remembers it as it is now.
     */
    public static boolean changed(String id, JSONObject field) {
        String now = signature(field);
        String known = knownContents.put(id, now);
        return known != null && !known.equals(now);
    }

    /** The value of a field's basic salvage (vanilla's BASIC drop: what a battle puts in it). */
    private static int basicValue(SectorEntityToken field) {
        int value = 0;
        if (field.getDropValue() == null) return 0;
        for (SalvageEntityGenDataSpec.DropData drop : field.getDropValue()) {
            if (Drops.BASIC.equals(drop.group)) value += drop.value;
        }
        return value;
    }

    /** A field's basic salvage set to this value (replacing what vanilla's addDebrisField puts in by default). */
    private static void setBasicValue(SectorEntityToken field, int value) {
        if (value <= 0) return;
        SalvageEntityGenDataSpec.DropData basic = null;
        for (SalvageEntityGenDataSpec.DropData drop : field.getDropValue()) {
            if (Drops.BASIC.equals(drop.group)) {
                if (basic == null) basic = drop;
                else drop.value = 0;
            }
        }
        if (basic == null) {
            basic = new SalvageEntityGenDataSpec.DropData();
            basic.group = Drops.BASIC;
            field.addDropValue(basic);
        }
        basic.value = value;
    }

    /** The ships that can be recovered from a field (the battle's wrecks): [{"variant", "condition", "name", "sMod"}]. */
    private static JSONArray recoverableShips(SectorEntityToken field) throws JSONException {
        JSONArray ships = new JSONArray();
        ShipRecoverySpecial.ShipRecoverySpecialData data = ShipRecoverySpecial.getSpecialData(field, null, false, false);
        if (data == null) return ships;
        for (ShipRecoverySpecial.PerShipData ship : data.ships) {
            String variant = ship.variantId != null ? ship.variantId : ship.variant != null ? ship.variant.getHullVariantId() : null;
            if (variant == null) continue;
            JSONObject entry = new JSONObject();
            entry.put("variant", variant);
            entry.put("condition", ship.condition == null ? ShipRecoverySpecial.ShipCondition.WRECKED.name() : ship.condition.name());
            if (ship.shipName != null) entry.put("name", ship.shipName);
            entry.put("sMod", ship.sModProb);
            ships.put(entry);
        }
        return ships;
    }

    /** A field's recoverable ships made the same as the other game's (none left there: none here either). */
    private static void setRecoverableShips(SectorEntityToken field, JSONArray ships) throws JSONException {
        if (ships == null) return;
        ShipRecoverySpecial.ShipRecoverySpecialData data = ShipRecoverySpecial.getSpecialData(field, null, ships.length() > 0, false);
        if (data == null) return;
        data.ships.clear();
        for (int i = 0; i < ships.length(); i++) {
            JSONObject entry = ships.getJSONObject(i);
            try {
                if (Global.getSettings().getVariant(entry.getString("variant")) == null) continue; //not in this game
            } catch (RuntimeException e) {
                continue;
            }
            ShipRecoverySpecial.ShipCondition condition;
            try {
                condition = ShipRecoverySpecial.ShipCondition.valueOf(entry.optString("condition", "WRECKED"));
            } catch (IllegalArgumentException e) {
                condition = ShipRecoverySpecial.ShipCondition.WRECKED;
            }
            ShipRecoverySpecial.PerShipData ship = new ShipRecoverySpecial.PerShipData(entry.getString("variant"), condition, (float) entry.optDouble("sMod", 0));
            if (entry.has("name")) ship.shipName = entry.getString("name");
            data.addShip(ship);
        }
    }

    /** Adds a field from another game (or updates it, a later battle having added to it). */
    public static void apply(String id, JSONObject field) throws JSONException {
        knownContents.put(id, signature(field));
        CampaignTerrainAPI existing = find(id);
        if (existing != null) {
            DebrisFieldTerrainPlugin plugin = (DebrisFieldTerrainPlugin) existing.getPlugin();
            DebrisFieldParams p = plugin.params;
            p.density = (float) field.getDouble("density");
            p.lastsDays += (float) field.getDouble("lastsDays") - plugin.getDaysLeft(); //its age here stays as it is
            p.baseSalvageXP = field.getLong("salvageXP");
            setBasicValue(existing, field.optInt("basic", 0));
            setRecoverableShips(existing, field.optJSONArray("ships"));
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
        setBasicValue(debris, field.optInt("basic", 0));
        setRecoverableShips(debris, field.optJSONArray("ships"));
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
