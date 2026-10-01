package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import matlabmaster.multiplayer.MultiplayerLog;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Players' colonies in the other games. A colony runs in its owner's game (the only authority on it, with the
 * vanilla colony screens); their game describes it, and the server and every other game put a mirror of it on that
 * planet: a market with its name, size, conditions and industries, owned by the owner's player faction, so it's on
 * the map and NPC fleets and other players see whose it is.
 *
 * A mirror is kept out of the economy: vanilla treats it like a market that isn't running (no production, trade,
 * patrols, station or raids, which would need the owner's game), so this game never simulates someone else's
 * colony. It remembers the planet's own (condition-only) market and faction and puts them back when it's removed.
 *
 * Colony description: {"id", "name", "entity": planet id, "size", "conditions": [id], "industries": [{"id", "building"}]}.
 */
public class ColonyMirrors {
    private static final String OWNER = "$mp_colonyOwner";
    private static final String ORIGINAL_MARKET = "$mp_originalMarket";
    private static final String ORIGINAL_FACTION = "$mp_originalFaction";
    private static final String ORIGINAL_NAME = "$mp_originalName";
    /** The modifier that makes a mirror's accessibility and stability its owner's. */
    private static final String AS_OWNER = "mp_as_owner";

    /** This game's player's colonies (owner's side). */
    public static JSONArray describeOwnColonies() throws JSONException {
        JSONArray colonies = new JSONArray();
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (!market.isPlayerOwned() || market.getPrimaryEntity() == null || isMirror(market)) continue;
            JSONObject colony = new JSONObject();
            colony.put("id", market.getId());
            colony.put("name", market.getName());
            colony.put("entity", market.getPrimaryEntity().getId());
            colony.put("planetName", market.getPrimaryEntity().getName()); //naming a colony renames its planet
            colony.put("size", market.getSize());
            colony.put("accessibility", Math.round(market.getAccessibilityMod().computeEffective(0f) * 100f) / 100d);
            colony.put("stability", Math.round(market.getStabilityValue()));
            JSONArray conditions = new JSONArray();
            for (MarketConditionAPI condition : market.getConditions()) conditions.put(condition.getId());
            colony.put("conditions", conditions);
            JSONArray industries = new JSONArray();
            for (Industry industry : market.getIndustries()) {
                JSONObject entry = new JSONObject();
                entry.put("id", industry.getId());
                entry.put("building", industry.isBuilding());
                industries.put(entry);
            }
            colony.put("industries", industries);
            colonies.put(colony);
        }
        return colonies;
    }

    public static boolean isMirror(MarketAPI market) {
        return market != null && market.getMemoryWithoutUpdate().contains(OWNER);
    }

    /**
     * Makes this game's mirrors of a player's colonies match their description: new ones added, changed ones
     * updated, gone ones removed. A colony on a planet that already has a market of its own here (someone else's
     * colony, or this game's player's) isn't mirrored.
     */
    public static void apply(String owner, String faction, JSONArray colonies) {
        Map<String, MarketAPI> mirrors = mirrorsOf(owner);
        Set<String> described = new HashSet<>();
        for (int i = 0; i < colonies.length(); i++) {
            try {
                JSONObject colony = colonies.getJSONObject(i);
                String id = colony.getString("id");
                described.add(id);
                MarketAPI mirror = mirrors.get(id);
                if (mirror == null) mirror = create(owner, faction, colony);
                if (mirror != null) update(mirror, faction, colony);
            } catch (Exception e) {
                MultiplayerLog.log().error("Couldn't mirror a colony of " + owner, e);
            }
        }
        for (Map.Entry<String, MarketAPI> mirror : mirrors.entrySet()) {
            if (!described.contains(mirror.getKey())) remove(mirror.getValue()); //abandoned, or lost
        }
    }

    /** Removes every mirror in this game (a client leaving the server: the world's colonies aren't its own). */
    public static void removeAll() {
        for (MarketAPI mirror : allMirrors()) remove(mirror);
    }

    private static MarketAPI create(String owner, String faction, JSONObject colony) throws JSONException {
        SectorEntityToken planet = Global.getSector().getEntityById(colony.getString("entity"));
        if (planet == null) {
            MultiplayerLog.log().warn("Can't mirror " + colony.optString("name") + ": its planet " + colony.getString("entity") + " isn't in this game");
            return null;
        }
        MarketAPI current = planet.getMarket();
        if (current != null && !current.isPlanetConditionMarketOnly()) {
            if (isMirror(current)) {
                remove(current); //another of this player's colonies here before (a new market id): replaced
                current = planet.getMarket();
            } else {
                //this game's own player's colony (the host's, or the same colony in a save copied from the owner's) is real here
                if (!current.isPlayerOwned()) {
                    MultiplayerLog.log().warn("Can't mirror " + colony.optString("name") + " of " + owner + ": " + planet.getName() + " already has a colony here");
                }
                return null;
            }
        }
        MarketAPI mirror = Global.getFactory().createMarket(colony.getString("id"), colony.getString("name"), colony.getInt("size"));
        mirror.setPrimaryEntity(planet);
        mirror.setFactionId(faction);
        mirror.setSurveyLevel(MarketAPI.SurveyLevel.FULL);
        MemoryAPI memory = mirror.getMemoryWithoutUpdate();
        memory.set(OWNER, owner);
        if (current != null) memory.set(ORIGINAL_MARKET, current);
        memory.set(ORIGINAL_FACTION, planet.getFaction() == null ? "neutral" : planet.getFaction().getId());
        memory.set(ORIGINAL_NAME, planet.getName());
        planet.setMarket(mirror);
        planet.setFaction(faction);
        MultiplayerLog.log().info("Mirrored " + colony.getString("name") + " (" + owner + ") on " + planet.getName());
        return mirror;
    }

    private static void update(MarketAPI mirror, String faction, JSONObject colony) throws JSONException {
        mirror.setName(colony.getString("name"));
        if (colony.has("planetName")) {
            //a mirror made before names were mirrored: its planet still has its own name
            if (!mirror.getMemoryWithoutUpdate().contains(ORIGINAL_NAME)) mirror.getMemoryWithoutUpdate().set(ORIGINAL_NAME, mirror.getPrimaryEntity().getName());
            mirror.getPrimaryEntity().setName(colony.getString("planetName"));
        }
        mirror.setSize(colony.getInt("size"));
        if (!faction.equals(mirror.getFactionId())) {
            mirror.setFactionId(faction);
            mirror.getPrimaryEntity().setFaction(faction);
        }

        Set<String> conditions = new HashSet<>();
        JSONArray wantedConditions = colony.getJSONArray("conditions");
        for (int i = 0; i < wantedConditions.length(); i++) conditions.add(wantedConditions.getString(i));
        for (MarketConditionAPI condition : new ArrayList<>(mirror.getConditions())) {
            if (!conditions.contains(condition.getId())) mirror.removeCondition(condition.getId());
        }
        for (String id : conditions) {
            if (mirror.hasCondition(id)) continue;
            try {
                mirror.addCondition(id);
            } catch (Exception e) {
                MultiplayerLog.log().warn("Couldn't add condition " + id + " to the mirror of " + mirror.getName() + ": " + e.getMessage());
            }
        }

        Map<String, Boolean> industries = new HashMap<>();
        JSONArray wantedIndustries = colony.getJSONArray("industries");
        for (int i = 0; i < wantedIndustries.length(); i++) {
            JSONObject industry = wantedIndustries.getJSONObject(i);
            industries.put(industry.getString("id"), industry.optBoolean("building"));
        }
        for (Industry industry : new ArrayList<>(mirror.getIndustries())) {
            if (!industries.containsKey(industry.getId())) mirror.removeIndustry(industry.getId(), null, false);
        }
        for (Map.Entry<String, Boolean> industry : industries.entrySet()) {
            if (mirror.hasIndustry(industry.getKey())) continue;
            try {
                mirror.addIndustry(industry.getKey());
                if (industry.getValue() && mirror.getIndustry(industry.getKey()) != null) {
                    mirror.getIndustry(industry.getKey()).startBuilding();
                }
            } catch (Exception e) {
                MultiplayerLog.log().warn("Couldn't add industry " + industry.getKey() + " to the mirror of " + mirror.getName() + ": " + e.getMessage());
            }
        }

        //a mirror isn't running (no admin, no spaceport bonus, no stability from the owner's choices): what it
        //would compute is meaningless, so it shows the owner's own numbers
        if (colony.has("accessibility")) {
            mirror.getAccessibilityMod().unmodifyFlat(AS_OWNER);
            float own = mirror.getAccessibilityMod().computeEffective(0f);
            mirror.getAccessibilityMod().modifyFlat(AS_OWNER, (float) colony.getDouble("accessibility") - own, "As in the owner's game");
        }
        if (colony.has("stability")) {
            mirror.getStability().unmodifyFlat(AS_OWNER);
            float own = mirror.getStability().getModifiedValue();
            mirror.getStability().modifyFlat(AS_OWNER, (float) colony.getDouble("stability") - own, "As in the owner's game");
        }
    }

    /** Puts the planet's own market, faction and name back. */
    private static void remove(MarketAPI mirror) {
        SectorEntityToken planet = mirror.getPrimaryEntity();
        MemoryAPI memory = mirror.getMemoryWithoutUpdate();
        if (planet != null && planet.getMarket() == mirror) {
            Object original = memory.get(ORIGINAL_MARKET);
            planet.setMarket(original instanceof MarketAPI ? (MarketAPI) original : null);
            planet.setFaction(memory.contains(ORIGINAL_FACTION) ? memory.getString(ORIGINAL_FACTION) : "neutral");
            if (memory.contains(ORIGINAL_NAME)) planet.setName(memory.getString(ORIGINAL_NAME));
        }
        MultiplayerLog.log().info("Removed the mirror of " + mirror.getName());
    }

    private static Map<String, MarketAPI> mirrorsOf(String owner) {
        Map<String, MarketAPI> mirrors = new HashMap<>();
        for (MarketAPI mirror : allMirrors()) {
            if (owner.equals(mirror.getMemoryWithoutUpdate().getString(OWNER))) mirrors.put(mirror.getId(), mirror);
        }
        return mirrors;
    }

    /** Every mirror in this game: markets on planets, not in the economy, so found through the planets. */
    private static List<MarketAPI> allMirrors() {
        List<MarketAPI> mirrors = new ArrayList<>();
        for (LocationAPI location : Global.getSector().getAllLocations()) {
            for (SectorEntityToken entity : location.getAllEntities()) {
                if (isMirror(entity.getMarket())) mirrors.add(entity.getMarket());
            }
        }
        return mirrors;
    }
}
