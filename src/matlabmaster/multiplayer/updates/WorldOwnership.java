package matlabmaster.multiplayer.updates;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.population.CoreImmigrationPluginImpl;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.utils.ColonyMirrors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Who owns what in the world: its markets (a faction's planets and stations change hands: Nexerelin's invasions,
 * a crisis takeover, a market growing or shrinking) and its objectives (comm relays, nav buoys and sensor arrays,
 * taken by fleets in fights over a system). The world's game is the authority; every other game shows the same
 * owners and sizes. Players' colonies aren't in it (ColonyMirrors), nor the server game's own player's ("player":
 * the host's colonies reach the others as a player's).
 *
 * State: {"markets": {marketId: {"faction", "size"}}, "objectives": {entityId: factionId}, "removedMarkets": [id]
 * (changes only: markets the world no longer has)}. A market the world has that this game doesn't is asked for
 * (WorldMarkets makes a copy of it).
 */
public class WorldOwnership {
    /** Markets the world has that this game doesn't (founded there: a new pirate base...): asked for once each. */
    private static final Set<String> unknown = new HashSet<>();

    /** World's side: who owns every market and objective now. */
    public static JSONObject describe() throws JSONException {
        JSONObject markets = new JSONObject();
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (ColonyMirrors.isMirror(market) || market.isPlayerOwned() || Factions.PLAYER.equals(market.getFactionId())) continue;
            markets.put(market.getId(), new JSONObject().put("faction", market.getFactionId()).put("size", market.getSize()));
        }
        JSONObject objectives = new JSONObject();
        for (LocationAPI location : Global.getSector().getAllLocations()) {
            for (SectorEntityToken entity : location.getEntitiesWithTag(Tags.OBJECTIVE)) {
                if (entity.getFaction() != null) objectives.put(entity.getId(), entity.getFaction().getId());
            }
        }
        return new JSONObject().put("markets", markets).put("objectives", objectives);
    }

    /** World's side: what changed between two describe()s (null if nothing did); the whole state if before is null. */
    public static JSONObject changes(JSONObject before, JSONObject now) throws JSONException {
        if (before == null) return now;
        JSONObject markets = changed(before.getJSONObject("markets"), now.getJSONObject("markets"));
        JSONObject objectives = changed(before.getJSONObject("objectives"), now.getJSONObject("objectives"));
        JSONArray removed = new JSONArray();
        for (Iterator<?> it = before.getJSONObject("markets").keys(); it.hasNext(); ) {
            String id = (String) it.next();
            if (!now.getJSONObject("markets").has(id)) removed.put(id);
        }
        if (markets.length() == 0 && objectives.length() == 0 && removed.length() == 0) return null;
        return new JSONObject().put("markets", markets).put("objectives", objectives).put("removedMarkets", removed);
    }

    private static JSONObject changed(JSONObject before, JSONObject now) throws JSONException {
        JSONObject changed = new JSONObject();
        for (Iterator<?> it = now.keys(); it.hasNext(); ) {
            String id = (String) it.next();
            Object value = now.get(id);
            Object was = before.opt(id);
            if (was == null || !String.valueOf(was).equals(String.valueOf(value))) changed.put(id, value);
        }
        return changed;
    }

    /**
     * A client: the world's owners (all of them on joining, then what changed), on this game's markets and
     * objectives. Returns the markets the world has and this game doesn't, not asked for yet (to ask the server).
     */
    public static List<String> apply(JSONObject state) throws JSONException {
        boolean announce = state.has("removedMarkets"); //changes, not the whole list on joining
        int changedMarkets = 0, changedObjectives = 0;
        List<String> missing = new ArrayList<>();
        JSONObject markets = state.optJSONObject("markets");
        if (markets != null) {
            for (Iterator<?> it = markets.keys(); it.hasNext(); ) {
                String id = (String) it.next();
                if (!WorldMarkets.has(id)) {
                    if (unknown.add(id)) missing.add(id);
                    continue;
                }
                if (applyMarket(id, markets.getJSONObject(id), announce)) changedMarkets++;
            }
        }
        if (!announce && markets != null) logOwnOnly(markets);
        JSONArray removed = state.optJSONArray("removedMarkets");
        if (removed != null) {
            for (int i = 0; i < removed.length(); i++) {
                unknown.remove(removed.getString(i));
                WorldMarkets.remove(removed.getString(i)); //only a copy of ours: this save's own markets are its own
            }
        }
        JSONObject objectives = state.optJSONObject("objectives");
        if (objectives != null) {
            for (Iterator<?> it = objectives.keys(); it.hasNext(); ) {
                String id = (String) it.next();
                SectorEntityToken entity = Global.getSector().getEntityById(id);
                String faction = objectives.getString(id);
                if (entity == null || Global.getSector().getFaction(faction) == null) continue;
                if (entity.getFaction() != null && faction.equals(entity.getFaction().getId())) continue;
                entity.setFaction(faction);
                changedObjectives++;
            }
        }
        if (changedMarkets > 0 || changedObjectives > 0) {
            MultiplayerLog.log().info("The world's owners: " + changedMarkets + " markets and " + changedObjectives + " objectives changed here");
        }
        return missing;
    }

    private static boolean applyMarket(String id, JSONObject wanted, boolean announce) throws JSONException {
        MarketAPI market = Global.getSector().getEconomy().getMarket(id);
        if (market == null) market = WorldMarkets.findCopy(id);
        if (market == null) return false;
        if (market.isPlayerOwned() || ColonyMirrors.isMirror(market)) return false; //ours, or a player's: theirs to say
        boolean changed = false;
        String faction = wanted.getString("faction");
        FactionAPI to = Global.getSector().getFaction(faction);
        if (to != null && !faction.equals(market.getFactionId())) {
            String from = market.getFactionId();
            market.setFactionId(faction);
            for (SectorEntityToken entity : market.getConnectedEntities()) entity.setFaction(faction);
            //its submarkets that were its owner's (open market, military market) are the new owner's
            for (SubmarketAPI submarket : market.getSubmarketsCopy()) {
                if (submarket.getFaction() != null && from.equals(submarket.getFaction().getId())) submarket.setFaction(to);
            }
            MultiplayerLog.log().info(market.getName() + " is " + to.getDisplayName() + "'s now (was " + from + ")");
            //news worth a line in the campaign log, as the player's own game would have shown it (not the whole
            //world's state on joining)
            if (announce && !market.isHidden() && Global.getSector().getCampaignUI() != null) {
                Global.getSector().getCampaignUI().addMessage(market.getName() + " is now " + to.getDisplayNameWithArticle() + "'s", to.getBaseUIColor());
            }
            changed = true;
        }
        int size = wanted.optInt("size", market.getSize());
        if (size >= 1 && size != market.getSize()) {
            market.removeCondition("population_" + market.getSize());
            market.addCondition("population_" + size);
            market.setSize(size);
            market.getPopulation().setWeight(CoreImmigrationPluginImpl.getWeightForMarketSizeStatic(size));
            market.getPopulation().normalize();
            market.reapplyConditions();
            market.reapplyIndustries();
            changed = true;
        }
        return changed;
    }

    /**
     * On joining: the markets this save has that the world doesn't (its own pirate bases, a colony a faction founded
     * in this save...). They stay, but they aren't the world's: trading there stays in this game. Logged once.
     */
    private static void logOwnOnly(JSONObject worldMarkets) {
        List<String> own = new ArrayList<>();
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (market.isPlayerOwned() || ColonyMirrors.isMirror(market) || worldMarkets.has(market.getId())) continue;
            own.add(market.getName() + " (" + market.getFactionId() + ")");
        }
        if (!own.isEmpty()) MultiplayerLog.log().info("This save has " + own.size() + " markets the world doesn't (trading there stays here): " + String.join(", ", own));
    }

    /** Left the server or loaded another game. */
    public static void reset() {
        unknown.clear();
    }
}
