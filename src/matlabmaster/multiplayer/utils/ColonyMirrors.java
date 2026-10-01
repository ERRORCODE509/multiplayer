package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.updates.MarketSync;
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
 * In the server's game (the world) a mirror is part of the economy, so the colony keeps working while its owner is
 * offline (see apply(..., inEconomy)); in the other games it's kept out of it, which vanilla treats like a market
 * that isn't running: those games only show it. It remembers the planet's own (condition-only) market and faction
 * and puts them back when it's removed.
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
            //what visitors can trade at (the open market, with Commerce), and on what terms
            JSONArray submarkets = new JSONArray();
            for (SubmarketAPI submarket : market.getSubmarketsCopy()) {
                if (!MarketSync.isTradable(submarket)) continue;
                JSONObject entry = new JSONObject();
                entry.put("id", submarket.getSpecId());
                if (submarket.getFaction() != null) entry.put("faction", submarket.getFaction().getId());
                submarkets.put(entry);
            }
            colony.put("submarkets", submarkets);
            colony.put("tariff", Math.round(market.getTariff().getModifiedValue() * 1000f) / 1000d);
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
        apply(owner, faction, colonies, false);
    }

    /**
     * The same; inEconomy for the server's game (the world), where a mirror is part of the economy: it produces,
     * trades, restocks, and its industries' patrols, station and trade fleets spawn (built from the owner's
     * blueprints, see PlayerFactions), so the colony keeps working while its owner is offline. Colony crises only
     * ever target the "player" faction's markets, so they never happen to it there: only in the owner's game.
     * The other games only show it.
     */
    public static void apply(String owner, String faction, JSONArray colonies, boolean inEconomy) {
        Map<String, MarketAPI> mirrors = mirrorsOf(owner);
        Set<String> described = new HashSet<>();
        for (int i = 0; i < colonies.length(); i++) {
            try {
                JSONObject colony = colonies.getJSONObject(i);
                String id = colony.getString("id");
                described.add(id);
                MarketAPI mirror = mirrors.get(id);
                if (mirror == null) mirror = create(owner, faction, colony);
                if (mirror != null) update(mirror, faction, colony, inEconomy);
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

    private static void update(MarketAPI mirror, String faction, JSONObject colony, boolean inEconomy) throws JSONException {
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
            if (mirror.hasIndustry(industry.getKey())) {
                //construction as in the owner's colony: started there, or finished
                Industry existing = mirror.getIndustry(industry.getKey());
                if (industry.getValue() && !existing.isBuilding()) existing.startBuilding();
                else if (!industry.getValue() && existing.isBuilding()) existing.finishBuildingOrUpgrading();
                continue;
            }
            try {
                mirror.addIndustry(industry.getKey());
                if (industry.getValue() && mirror.getIndustry(industry.getKey()) != null) {
                    mirror.getIndustry(industry.getKey()).startBuilding();
                }
            } catch (Exception e) {
                MultiplayerLog.log().warn("Couldn't add industry " + industry.getKey() + " to the mirror of " + mirror.getName() + ": " + e.getMessage());
            }
        }

        //the submarkets visitors can trade at, as the owner's colony has them (their stock comes per visit)
        JSONArray wantedSubmarkets = colony.optJSONArray("submarkets");
        if (wantedSubmarkets != null) {
            Map<String, String> submarkets = new HashMap<>();
            for (int i = 0; i < wantedSubmarkets.length(); i++) {
                JSONObject entry = wantedSubmarkets.getJSONObject(i);
                submarkets.put(entry.getString("id"), entry.optString("faction", null));
            }
            for (SubmarketAPI submarket : mirror.getSubmarketsCopy()) {
                if (!submarkets.containsKey(submarket.getSpecId())) mirror.removeSubmarket(submarket.getSpecId());
            }
            for (Map.Entry<String, String> submarket : submarkets.entrySet()) {
                try {
                    if (!mirror.hasSubmarket(submarket.getKey())) mirror.addSubmarket(submarket.getKey());
                    FactionAPI owner = submarket.getValue() == null ? null : Global.getSector().getFaction(submarket.getValue());
                    if (owner != null) mirror.getSubmarket(submarket.getKey()).setFaction(owner);
                } catch (Exception e) {
                    MultiplayerLog.log().warn("Couldn't add submarket " + submarket.getKey() + " to the mirror of " + mirror.getName() + ": " + e.getMessage());
                }
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
            float stability = (float) colony.getDouble("stability");
            //in the economy, vanilla decivilizes a market that stays at 0 stability: never the world's copy of
            //someone's colony (whether theirs does is their game's business)
            if (inEconomy) stability = Math.max(1f, stability);
            mirror.getStability().modifyFlat(AS_OWNER, stability - own, "As in the owner's game");
        }
        if (colony.has("tariff")) {
            mirror.getTariff().unmodifyFlat(AS_OWNER);
            float own = mirror.getTariff().getModifiedValue();
            mirror.getTariff().modifyFlat(AS_OWNER, (float) colony.getDouble("tariff") - own, "As in the owner's game");
        }

        //last, once it's all set up, like a new market: the world's economy (mirrors made before join it here)
        if (inEconomy && !mirror.isInEconomy()) {
            Global.getSector().getEconomy().addMarket(mirror, false);
            MultiplayerLog.log().info(mirror.getName() + " is part of the world's economy");
        }
    }

    /** The mirror of a player's colony market in this game, or null. */
    public static MarketAPI find(String marketId) {
        MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
        if (isMirror(market)) return market; //in the economy (the server's game)
        for (MarketAPI mirror : allMirrors()) {
            if (mirror.getId().equals(marketId)) return mirror;
        }
        return null;
    }

    /** Whose colony a mirror is (their permanent player id). */
    public static String ownerOf(MarketAPI mirror) {
        return mirror.getMemoryWithoutUpdate().getString(OWNER);
    }

    /** Takes it out of the economy (if it's in) and puts the planet's own market, faction and name back. */
    private static void remove(MarketAPI mirror) {
        if (mirror.isInEconomy()) Global.getSector().getEconomy().removeMarket(mirror);
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

    /** Every mirror in this game, found through the planets (only the server's are in the economy). */
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
