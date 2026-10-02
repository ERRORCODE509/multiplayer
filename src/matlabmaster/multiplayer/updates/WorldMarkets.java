package matlabmaster.multiplayer.updates;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.utils.ColonyMirrors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Markets the world founded that a player's game doesn't have: pirate and Luddic Path bases (a new station),
 * colonies a faction founded (Nexerelin's colony expeditions: a market on a planet). The player's game asks for
 * them (WorldOwnership lists every world market) and makes a copy: the station if it has none, the market on it,
 * shown and traded at like the world's (its stock comes from the server, see MarketSync), kept out of this game's
 * economy. Copies go when the world's market does, and on leaving the server (they're the world's, not this save's).
 *
 * Market: {"id", "name", "faction", "size", "hidden", "tariff", "conditions": [id], "industries": [id],
 * "submarkets": [id], "entity": {"id", "name", "type" (custom entity type, none for a planet), "location",
 * "focus", "angle", "radius", "period", "x", "y", "discoverable", "sensorProfile"}}.
 */
public class WorldMarkets {
    /** On a copy's market, and on a station made for one (so only those are ever removed). */
    private static final String COPY = "$mp_worldMarketCopy";
    private static final String MADE_ENTITY = "$mp_worldEntityCopy";
    private static final String ORIGINAL_MARKET = "$mp_originalMarket";

    /** World's side: a market, as a player's game needs it to make a copy. */
    public static JSONObject describe(MarketAPI market) throws JSONException {
        SectorEntityToken entity = market.getPrimaryEntity();
        if (entity == null || entity.getContainingLocation() == null) return null;
        JSONObject json = new JSONObject();
        json.put("id", market.getId());
        json.put("name", market.getName());
        json.put("faction", market.getFactionId());
        json.put("size", market.getSize());
        json.put("hidden", market.isHidden());
        json.put("tariff", market.getTariff().getModifiedValue());
        JSONArray conditions = new JSONArray();
        for (MarketConditionAPI condition : market.getConditions()) conditions.put(condition.getId());
        json.put("conditions", conditions);
        JSONArray industries = new JSONArray();
        for (Industry industry : market.getIndustries()) industries.put(industry.getId());
        json.put("industries", industries);
        JSONArray submarkets = new JSONArray();
        for (SubmarketAPI submarket : market.getSubmarketsCopy()) submarkets.put(submarket.getSpecId());
        json.put("submarkets", submarkets);
        JSONObject e = new JSONObject();
        e.put("id", entity.getId());
        e.put("name", entity.getName());
        if (entity instanceof CustomCampaignEntityAPI) e.put("type", entity.getCustomEntityType());
        e.put("location", entity.getContainingLocation().getId());
        if (entity.getOrbitFocus() != null) {
            e.put("focus", entity.getOrbitFocus().getId());
            e.put("angle", entity.getCircularOrbitAngle());
            e.put("radius", entity.getCircularOrbitRadius());
            e.put("period", entity.getCircularOrbitPeriod());
        }
        e.put("x", entity.getLocation().x);
        e.put("y", entity.getLocation().y);
        e.put("discoverable", entity.isDiscoverable());
        e.put("sensorProfile", entity.getSensorProfile());
        json.put("entity", e);
        return json;
    }

    /** A player's game: the copy of a world market it doesn't have. */
    public static void create(JSONObject json) throws JSONException {
        String id = json.getString("id");
        if (Global.getSector().getEconomy().getMarket(id) != null || findCopy(id) != null) return; //has it already
        JSONObject e = json.getJSONObject("entity");
        SectorEntityToken entity = Global.getSector().getEntityById(e.getString("id"));
        boolean made = false;
        if (entity == null) {
            if (!e.has("type")) {
                MultiplayerLog.log().warn("The world's market " + json.optString("name") + " is on " + e.getString("id") + ", which this game doesn't have");
                return;
            }
            LocationAPI location = location(e.getString("location"));
            if (location == null) return;
            entity = location.addCustomEntity(e.getString("id"), e.optString("name", null), e.getString("type"), json.getString("faction"));
            SectorEntityToken focus = e.has("focus") ? Global.getSector().getEntityById(e.getString("focus")) : null;
            if (focus != null) {
                entity.setCircularOrbitWithSpin(focus, (float) e.getDouble("angle"), (float) e.getDouble("radius"), (float) e.getDouble("period"), -5f, -5f);
            } else {
                entity.setFixedLocation((float) e.getDouble("x"), (float) e.getDouble("y"));
            }
            if (e.has("discoverable")) entity.setDiscoverable(e.getBoolean("discoverable"));
            if (e.has("sensorProfile")) entity.setSensorProfile((float) e.getDouble("sensorProfile"));
            entity.getMemoryWithoutUpdate().set(MADE_ENTITY, true);
            made = true;
        } else if (entity.getMarket() != null && !entity.getMarket().isPlanetConditionMarketOnly()) {
            return; //something of this game's own is there (a colony): left alone
        }

        MarketAPI market = Global.getFactory().createMarket(id, json.getString("name"), json.getInt("size"));
        market.setFactionId(json.getString("faction"));
        market.setHidden(json.optBoolean("hidden"));
        market.setSurveyLevel(MarketAPI.SurveyLevel.FULL);
        JSONArray conditions = json.getJSONArray("conditions");
        for (int i = 0; i < conditions.length(); i++) {
            try {
                market.addCondition(conditions.getString(i));
            } catch (Exception ex) {
                MultiplayerLog.log().warn("Couldn't add condition " + conditions.getString(i) + " to the copy of " + json.getString("name"));
            }
        }
        JSONArray industries = json.getJSONArray("industries");
        for (int i = 0; i < industries.length(); i++) {
            try {
                market.addIndustry(industries.getString(i));
            } catch (Exception ex) {
                MultiplayerLog.log().warn("Couldn't add industry " + industries.getString(i) + " to the copy of " + json.getString("name"));
            }
        }
        JSONArray submarkets = json.getJSONArray("submarkets");
        for (int i = 0; i < submarkets.length(); i++) {
            try {
                market.addSubmarket(submarkets.getString(i));
            } catch (Exception ex) {
                MultiplayerLog.log().warn("Couldn't add submarket " + submarkets.getString(i) + " to the copy of " + json.getString("name"));
            }
        }
        if (json.has("tariff")) market.getTariff().modifyFlat("mp_world", (float) json.getDouble("tariff"), "As in the world");
        MemoryAPI memory = market.getMemoryWithoutUpdate();
        memory.set(COPY, true);
        if (!made && entity.getMarket() != null) memory.set(ORIGINAL_MARKET, entity.getMarket());
        market.setPrimaryEntity(entity);
        entity.setMarket(market);
        entity.setFaction(json.getString("faction"));
        MultiplayerLog.log().info("The world's " + json.getString("name") + " (" + json.getString("faction") + ") is here too" + (made ? " (with its station)" : ""));
    }

    /** A player's game: the world's market is gone (a base destroyed, a colony lost): so is the copy. */
    public static boolean remove(String id) {
        MarketAPI market = findCopy(id);
        if (market == null) return false;
        SectorEntityToken entity = market.getPrimaryEntity();
        if (entity != null) {
            if (entity.getMemoryWithoutUpdate().getBoolean(MADE_ENTITY)) {
                if (entity.getContainingLocation() != null) entity.getContainingLocation().removeEntity(entity);
            } else if (entity.getMarket() == market) {
                Object original = market.getMemoryWithoutUpdate().get(ORIGINAL_MARKET);
                entity.setMarket(original instanceof MarketAPI ? (MarketAPI) original : null);
                entity.setFaction(original instanceof MarketAPI ? ((MarketAPI) original).getFactionId() : "neutral");
            }
        }
        MultiplayerLog.log().info("The world's " + market.getName() + " is gone: so is the copy here");
        return true;
    }

    /** Every copy, on leaving the server or joining one (the world's, never this save's). */
    public static int removeAll() {
        int removed = 0;
        for (MarketAPI copy : allCopies()) {
            if (remove(copy.getId())) removed++;
        }
        return removed;
    }

    /** Whether this game has a market with this id (its own, or a copy). */
    public static boolean has(String id) {
        return Global.getSector().getEconomy().getMarket(id) != null || findCopy(id) != null;
    }

    public static MarketAPI findCopy(String id) {
        for (MarketAPI copy : allCopies()) {
            if (copy.getId().equals(id)) return copy;
        }
        return null;
    }

    private static List<MarketAPI> allCopies() {
        List<MarketAPI> copies = new ArrayList<>();
        for (LocationAPI location : Global.getSector().getAllLocations()) {
            for (SectorEntityToken entity : location.getAllEntities()) {
                MarketAPI market = entity.getMarket();
                if (market != null && market.getMemoryWithoutUpdate().getBoolean(COPY) && !ColonyMirrors.isMirror(market)) copies.add(market);
            }
        }
        return copies;
    }

    private static LocationAPI location(String id) {
        if ("hyperspace".equals(id) || Global.getSector().getHyperspace().getId().equals(id)) return Global.getSector().getHyperspace();
        for (LocationAPI location : Global.getSector().getAllLocations()) {
            if (location.getId().equals(id)) return location;
        }
        return null;
    }
}
