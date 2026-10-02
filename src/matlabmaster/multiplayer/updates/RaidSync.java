package matlabmaster.multiplayer.updates;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.econ.RecentUnrest;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker;
import com.fs.starfarer.api.impl.campaign.population.CoreImmigrationPluginImpl;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.group.BlockadeFGI;
import com.fs.starfarer.api.impl.campaign.intel.group.FGBlockadeAction;
import com.fs.starfarer.api.impl.campaign.intel.group.KnightsOfLuddTakeoverExpedition;
import com.fs.starfarer.api.impl.campaign.intel.group.PerseanLeagueBlockade;
import com.fs.starfarer.api.impl.campaign.intel.group.FGRaidAction;
import com.fs.starfarer.api.impl.campaign.intel.group.FleetGroupIntel;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.impl.campaign.missions.FleetCreatorMission;
import com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionWithTriggers;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.util.Misc;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.utils.ColonyMirrors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * Raids on players' colonies (the colony crises' raids and expeditions: GenericRaidFGI and its subclasses) happen in
 * the world, where every player sees their fleets and can fight them. The colony's owner's game makes the raid (its
 * crisis decides when), describes it to the server and keeps its own, frozen (see client.OwnRaids); the server's
 * game makes the same raid on the world's copy of the colony and runs it (see server.ServerRaids). What the raid
 * did to the copy goes back to the owner's real colony, and how the raid went (the actions done, defeated or over)
 * to the owner's raid, which then ends as it would have: a defeated raid is a defeated raid for their crisis,
 * whoever beat it.
 *
 * Blockades (Persean League, the Knights of Ludd's takeover) too: the world runs their fleets, and what they do
 * to the colony stays the owner's game's, as the world says how far along they are: the League's blockade
 * condition (accessibility) and the Knights' monthly unrest and takeover (their vanilla code, on the real colony).
 *
 * Raid: {"id", "class", "factionId", "source": market id, "fleetSizes": [int], "style", "prepDays", "payloadDays",
 * "makeFleetsHostile", "repImpact", "noun", "forcesNoun", "remnant", "playerTargeted", "raid": {FGRaidParams},
 * "blockade": {FGBlockadeParams} (blockades only)}.
 */
public class RaidSync {
    /** How a raid the world ran ended: defeated or called off; over; never started there (it couldn't be made). */
    public static final String ABORTED = "aborted", FINISHED = "finished", CANCELLED = "cancelled";

    /** Whether the world can run this raid for its owner: a raid or blockade on one of this game's player's colonies. */
    public static boolean canHandOver(FleetGroupIntel intel) {
        if (!(intel instanceof GenericRaidFGI)) return false;
        GenericRaidFGI raid = (GenericRaidFGI) intel;
        if (raid.getParams() == null || raid.getParams().source == null || target(raid) == null) return false;
        if (raid instanceof BlockadeFGI) {
            FGBlockadeAction.FGBlockadeParams blockade = ((BlockadeFGI) raid).getBlockadeParams();
            if (blockade == null || !Factions.PLAYER.equals(blockade.targetFaction)) return false; //only ours
        } else if (!(raid.getRaidAction() instanceof FGRaidAction) || raid.getParams().raidParams == null) {
            return false;
        }
        for (MarketAPI market : Misc.getMarketsInLocation(target(raid))) {
            if (market.isPlayerOwned() && !ColonyMirrors.isMirror(market)) return true;
        }
        return false;
    }

    /** The system it's going to (a blockade has it in its own params, not the raid params). */
    public static StarSystemAPI target(GenericRaidFGI raid) {
        return raid.getRaidAction() == null ? null : raid.getRaidAction().getWhere();
    }

    /** Owner's side: the raid as the world needs it to make the same. */
    public static JSONObject describe(String id, GenericRaidFGI raid) throws JSONException {
        GenericRaidFGI.GenericRaidParams params = raid.getParams();
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("class", raid.getClass().getName());
        json.put("factionId", params.factionId);
        json.put("source", params.source.getId());
        json.put("fleetSizes", new JSONArray(params.fleetSizes));
        json.put("style", params.style == null ? null : params.style.name());
        //what's left of it: a raid made before joining may be on its way already (then it starts out from its source)
        json.put("prepDays", raid.isInPreLaunchDelay() ? raid.getDelayRemaining() : 0f);
        json.put("payloadDays", params.payloadDays);
        json.put("makeFleetsHostile", params.makeFleetsHostile);
        json.put("repImpact", params.repImpact == null ? null : params.repImpact.name());
        json.put("noun", params.noun);
        json.put("forcesNoun", params.forcesNoun);
        json.put("remnant", params.remnant);
        json.put("playerTargeted", params.playerTargeted);

        FGRaidAction.FGRaidParams r = params.raidParams;
        JSONObject raidJson = new JSONObject();
        raidJson.put("where", target(raid).getId());
        raidJson.put("type", r.type == null ? null : r.type.name());
        raidJson.put("doNotGetSidetracked", r.doNotGetSidetracked);
        raidJson.put("tryToCaptureObjectives", r.tryToCaptureObjectives);
        raidJson.put("allowAnyHostileMarket", r.allowAnyHostileMarket);
        raidJson.put("maxDurationIfSpawnedFleetsConcurrent", r.maxDurationIfSpawnedFleetsConcurrent);
        raidJson.put("maxDurationIfSpawnedFleetsPerSequentialStage", r.maxDurationIfSpawnedFleetsPerSequentialStage);
        raidJson.put("maxStabilityLostPerRaid", r.maxStabilityLostPerRaid);
        raidJson.put("raidsPerColony", r.raidsPerColony);
        raidJson.put("raidApproachText", r.raidApproachText);
        raidJson.put("raidActionText", r.raidActionText);
        raidJson.put("targetTravelText", r.targetTravelText);
        raidJson.put("appendTargetNameToTravelText", r.appendTargetNameToTravelText);
        raidJson.put("inSystemActionText", r.inSystemActionText);
        raidJson.put("bombardment", r.bombardment == null ? null : r.bombardment.name());
        raidJson.put("disrupt", new JSONArray(r.disrupt));
        raidJson.put("allowNonHostileTargets", r.allowNonHostileTargets);
        JSONArray targets = new JSONArray();
        for (MarketAPI market : r.allowedTargets) targets.put(market.getId());
        raidJson.put("allowedTargets", targets);
        json.put("raid", raidJson);
        if (raid instanceof BlockadeFGI) {
            FGBlockadeAction.FGBlockadeParams b = ((BlockadeFGI) raid).getBlockadeParams();
            JSONObject blockade = new JSONObject();
            blockade.put("where", b.where == null ? target(raid).getId() : b.where.getId());
            if (b.specificMarket != null) blockade.put("specificMarket", b.specificMarket.getId());
            blockade.put("doNotGetSidetracked", b.doNotGetSidetracked);
            blockade.put("accessibilityPenalty", b.accessibilityPenalty);
            blockade.put("patrolText", b.patrolText);
            json.put("blockade", blockade);
        }
        return json;
    }

    /**
     * World's side: the same raid here, on the world's copies of the colonies (they have the colonies' market ids),
     * or null if it can't be (logged).
     */
    public static GenericRaidFGI create(JSONObject json, String ownerFaction) throws JSONException {
        JSONObject raidJson = json.getJSONObject("raid");
        StarSystemAPI where = Global.getSector().getStarSystem(raidJson.getString("where"));
        if (where == null) where = systemById(raidJson.getString("where"));
        if (where == null) {
            MultiplayerLog.log().warn("A raid's target system " + raidJson.getString("where") + " isn't in the world");
            return null;
        }
        String factionId = json.getString("factionId");
        if (Global.getSector().getFaction(factionId) == null) {
            MultiplayerLog.log().warn("A raid's faction " + factionId + " isn't in the world");
            return null;
        }
        MarketAPI source = Global.getSector().getEconomy().getMarket(json.getString("source"));
        if (source == null || source.getPrimaryEntity() == null) source = nearestMarket(factionId, where);
        if (source == null) {
            MultiplayerLog.log().warn("A raid of " + factionId + " has nowhere to start from in the world");
            return null;
        }

        GenericRaidFGI.GenericRaidParams params = new GenericRaidFGI.GenericRaidParams(new Random(), json.optBoolean("playerTargeted", true));
        params.factionId = factionId;
        params.source = source;
        JSONArray sizes = json.getJSONArray("fleetSizes");
        for (int i = 0; i < sizes.length(); i++) params.fleetSizes.add(sizes.getInt(i));
        if (json.has("style")) params.style = FleetCreatorMission.FleetStyle.valueOf(json.getString("style"));
        params.prepDays = (float) json.optDouble("prepDays", 0);
        params.payloadDays = (float) json.optDouble("payloadDays", params.payloadDays);
        params.makeFleetsHostile = json.optBoolean("makeFleetsHostile", true);
        if (json.has("repImpact")) params.repImpact = HubMissionWithTriggers.ComplicationRepImpact.valueOf(json.getString("repImpact"));
        if (json.has("noun")) params.noun = json.getString("noun");
        if (json.has("forcesNoun")) params.forcesNoun = json.getString("forcesNoun");
        params.remnant = json.optBoolean("remnant", false);
        //memoryKey stays null: the owner's crisis keeps its raid under its key in their game; here it'd be the host's

        FGRaidAction.FGRaidParams r = params.raidParams;
        r.where = where;
        if (raidJson.has("type")) r.type = FGRaidAction.FGRaidType.valueOf(raidJson.getString("type"));
        r.doNotGetSidetracked = raidJson.optBoolean("doNotGetSidetracked", r.doNotGetSidetracked);
        r.tryToCaptureObjectives = raidJson.optBoolean("tryToCaptureObjectives", r.tryToCaptureObjectives);
        r.allowAnyHostileMarket = raidJson.optBoolean("allowAnyHostileMarket", r.allowAnyHostileMarket);
        r.maxDurationIfSpawnedFleetsConcurrent = (float) raidJson.optDouble("maxDurationIfSpawnedFleetsConcurrent", r.maxDurationIfSpawnedFleetsConcurrent);
        r.maxDurationIfSpawnedFleetsPerSequentialStage = (float) raidJson.optDouble("maxDurationIfSpawnedFleetsPerSequentialStage", r.maxDurationIfSpawnedFleetsPerSequentialStage);
        r.maxStabilityLostPerRaid = raidJson.optInt("maxStabilityLostPerRaid", r.maxStabilityLostPerRaid);
        r.raidsPerColony = raidJson.optInt("raidsPerColony", r.raidsPerColony);
        if (raidJson.has("raidApproachText")) r.raidApproachText = raidJson.getString("raidApproachText");
        if (raidJson.has("raidActionText")) r.raidActionText = raidJson.getString("raidActionText");
        if (raidJson.has("targetTravelText")) r.targetTravelText = raidJson.getString("targetTravelText");
        r.appendTargetNameToTravelText = raidJson.optBoolean("appendTargetNameToTravelText", r.appendTargetNameToTravelText);
        if (raidJson.has("inSystemActionText")) r.inSystemActionText = raidJson.getString("inSystemActionText");
        if (raidJson.has("bombardment")) r.bombardment = MarketCMD.BombardType.valueOf(raidJson.getString("bombardment"));
        JSONArray disrupt = raidJson.optJSONArray("disrupt");
        if (disrupt != null) for (int i = 0; i < disrupt.length(); i++) r.disrupt.add(disrupt.getString(i));
        r.allowNonHostileTargets = raidJson.optBoolean("allowNonHostileTargets", r.allowNonHostileTargets);
        JSONArray targets = raidJson.optJSONArray("allowedTargets");
        if (targets != null) {
            for (int i = 0; i < targets.length(); i++) {
                MarketAPI market = Global.getSector().getEconomy().getMarket(targets.getString(i)); //a colony's copy has its id
                if (market != null) r.allowedTargets.add(market);
            }
        }

        GenericRaidFGI raid;
        JSONObject blockadeJson = json.optJSONObject("blockade");
        if (blockadeJson != null) {
            if (ownerFaction == null) return null; //a blockade of the owner's faction: they need one here
            FGBlockadeAction.FGBlockadeParams b = new FGBlockadeAction.FGBlockadeParams();
            b.where = where;
            if (blockadeJson.has("specificMarket")) {
                b.specificMarket = Global.getSector().getEconomy().getMarket(blockadeJson.getString("specificMarket"));
                if (b.specificMarket == null) {
                    MultiplayerLog.log().warn("A blockade's colony " + blockadeJson.getString("specificMarket") + " isn't in the world");
                    return null;
                }
            }
            b.doNotGetSidetracked = blockadeJson.optBoolean("doNotGetSidetracked", b.doNotGetSidetracked);
            b.accessibilityPenalty = (float) blockadeJson.optDouble("accessibilityPenalty", b.accessibilityPenalty);
            if (blockadeJson.has("patrolText")) b.patrolText = blockadeJson.getString("patrolText");
            b.targetFaction = ownerFaction; //"player" in their game: the colonies' copies are theirs here
            raid = constructBlockade(json.optString("class"), params, b);
        } else {
            raid = construct(json.optString("class"), params);
        }
        MultiplayerLog.log().info("The world runs a " + raid.getBaseName() + " on " + where.getName() + " (from " + source.getName() + ")");
        return raid;
    }

    /**
     * The owner's class of raid (vanilla's subclasses: punitive expeditions, mercenaries; or another mod's) if it can
     * be made from its params, else a plain one. Whatever it puts in the sector's memory for itself (a subclass's
     * "the current expedition" key) is put back as it was: that's the host's crisis's, not this raid's.
     */
    private static GenericRaidFGI construct(String className, GenericRaidFGI.GenericRaidParams params) {
        Map<String, Object> before = memoryRefs();
        GenericRaidFGI raid = null;
        try {
            Class<?> cls = Global.getSettings().getScriptClassLoader().loadClass(className);
            if (GenericRaidFGI.class.isAssignableFrom(cls) && !BlockadeFGI.class.isAssignableFrom(cls)) {
                raid = (GenericRaidFGI) cls.getConstructor(GenericRaidFGI.GenericRaidParams.class).newInstance(params);
            }
        } catch (Throwable e) {
            MultiplayerLog.log().warn("Couldn't make a " + className + " here (" + e + "): a plain raid instead");
        }
        if (raid == null) raid = new GenericRaidFGI(params);
        restoreMemoryRefs(before, raid);
        return raid;
    }

    /**
     * The same for blockades: vanilla's two as the world's versions (WorldBlockades: they'd call themselves off
     * without the host having a colony crisis of their own), another mod's if it can be made, else a plain one.
     */
    private static GenericRaidFGI constructBlockade(String className, GenericRaidFGI.GenericRaidParams params, FGBlockadeAction.FGBlockadeParams blockade) {
        Map<String, Object> before = memoryRefs();
        GenericRaidFGI raid = null;
        try {
            if (PerseanLeagueBlockade.class.getName().equals(className)) {
                raid = new WorldBlockades.LeagueBlockade(params, blockade);
            } else if (KnightsOfLuddTakeoverExpedition.class.getName().equals(className)) {
                raid = new WorldBlockades.TakeoverExpedition(params, blockade);
            } else {
                Class<?> cls = Global.getSettings().getScriptClassLoader().loadClass(className);
                if (BlockadeFGI.class.isAssignableFrom(cls)) {
                    raid = (GenericRaidFGI) cls.getConstructor(GenericRaidFGI.GenericRaidParams.class, FGBlockadeAction.FGBlockadeParams.class).newInstance(params, blockade);
                }
            }
        } catch (Throwable e) {
            MultiplayerLog.log().warn("Couldn't make a " + className + " here (" + e + "): a plain blockade instead");
        }
        if (raid == null) raid = new BlockadeFGI(params, blockade);
        restoreMemoryRefs(before, raid);
        return raid;
    }

    /** The FleetGroupIntels the sector's memory points to (a subclass's "the current one" key), see construct. */
    private static Map<String, Object> memoryRefs() {
        MemoryAPI memory = Global.getSector().getMemoryWithoutUpdate();
        Map<String, Object> refs = new HashMap<>();
        for (String key : memory.getKeys()) {
            if (memory.get(key) instanceof FleetGroupIntel) refs.put(key, memory.get(key));
        }
        return refs;
    }

    private static void restoreMemoryRefs(Map<String, Object> before, GenericRaidFGI raid) {
        MemoryAPI memory = Global.getSector().getMemoryWithoutUpdate();
        for (String key : memory.getKeys().toArray(new String[0])) {
            if (memory.get(key) != raid) continue;
            if (before.containsKey(key)) memory.set(key, before.get(key));
            else memory.unset(key);
        }
    }

    private static StarSystemAPI systemById(String id) {
        for (StarSystemAPI system : Global.getSector().getStarSystems()) {
            if (id.equals(system.getId())) return system;
        }
        return null;
    }

    /** The faction's market nearest to the target, for a raid whose source isn't in the world. */
    private static MarketAPI nearestMarket(String factionId, StarSystemAPI where) {
        MarketAPI best = null;
        float bestDistance = Float.MAX_VALUE;
        for (MarketAPI market : Misc.getFactionMarkets(factionId)) {
            if (market.getPrimaryEntity() == null || ColonyMirrors.isMirror(market)) continue;
            float distance = Misc.getDistanceLY(market.getPrimaryEntity().getLocationInHyperspace(), where.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = market;
            }
        }
        return best;
    }

    // --- what the world did to a player's colony's copy ---

    /** World's side: what a copy of a player's colony looks like now, to tell what the world just did to it. */
    public static JSONObject colonyState(MarketAPI mirror) throws JSONException {
        JSONObject state = new JSONObject();
        RecentUnrest unrest = RecentUnrest.get(mirror, false);
        state.put("unrest", unrest == null ? 0 : unrest.getPenalty());
        JSONObject disrupted = new JSONObject();
        for (Industry industry : mirror.getIndustries()) {
            if (industry.getDisruptedDays() > 0) disrupted.put(industry.getId(), industry.getDisruptedDays());
        }
        state.put("disrupted", disrupted);
        state.put("pollution", mirror.hasCondition(Conditions.POLLUTION));
        state.put("size", mirror.getSize());
        state.put("bombarded", mirror.getMemoryWithoutUpdate().contains(MemFlags.RECENTLY_BOMBARDED));
        return state;
    }

    /**
     * World's side: what happened to a copy between two states (a raid, a bombardment), or null if nothing did:
     * {"market", "unrest": points, "reason", "disrupted": {industry: days}, "pollution": true, "sizeLoss": n}.
     * Its size only counts as lost to a saturation bombardment (it's bombarded and smaller); any other change of size
     * is its owner's (ServerRaids compares only across the world's own changes, never the owner's updates).
     */
    public static JSONObject hit(MarketAPI mirror, JSONObject before, JSONObject now) throws JSONException {
        JSONObject hit = new JSONObject();
        int unrest = now.getInt("unrest") - before.getInt("unrest");
        if (unrest > 0) {
            hit.put("unrest", unrest);
            hit.put("reason", latestReason(mirror));
        }
        JSONObject disrupted = new JSONObject();
        JSONObject was = before.getJSONObject("disrupted"), is = now.getJSONObject("disrupted");
        for (Iterator<?> it = is.keys(); it.hasNext(); ) {
            String id = (String) it.next();
            //disruption only counts down by itself: more than a second ago is new
            if (is.getDouble(id) > was.optDouble(id, 0) + 0.5) disrupted.put(id, is.getDouble(id));
        }
        if (disrupted.length() > 0) hit.put("disrupted", disrupted);
        if (now.getBoolean("pollution") && !before.getBoolean("pollution")) hit.put("pollution", true);
        int sizeLoss = before.optInt("size", 0) - now.optInt("size", 0);
        if (sizeLoss > 0 && now.optBoolean("bombarded")) hit.put("sizeLoss", sizeLoss);
        if (hit.length() == 0) return null;
        hit.put("market", mirror.getId());
        return hit;
    }

    /** The reason given for the latest unrest (the one with the most time left), as the colony screen lists it. */
    private static String latestReason(MarketAPI mirror) {
        RecentUnrest unrest = RecentUnrest.get(mirror, false);
        String best = "Raid";
        float bestDays = -1f;
        if (unrest == null) return best;
        try {
            java.lang.reflect.Field field = RecentUnrest.class.getDeclaredField("reasons");
            field.setAccessible(true);
            com.fs.starfarer.api.util.TimeoutTracker<?> reasons = (com.fs.starfarer.api.util.TimeoutTracker<?>) field.get(unrest);
            for (Object reason : reasons.getItems()) {
                @SuppressWarnings("unchecked")
                float days = ((com.fs.starfarer.api.util.TimeoutTracker<Object>) reasons).getRemaining(reason);
                if (days > bestDays) {
                    bestDays = days;
                    best = String.valueOf(reason);
                }
            }
        } catch (Exception ignored) { }
        return best;
    }

    /** Owner's side: the world's raid (or bombardment) on the copy of one of this game's colonies, on the colony. */
    public static void applyHit(JSONObject hit) throws JSONException {
        MarketAPI market = Global.getSector().getEconomy().getMarket(hit.getString("market"));
        if (market == null || !market.isPlayerOwned() || ColonyMirrors.isMirror(market)) {
            MultiplayerLog.log().warn("The world hit colony " + hit.getString("market") + ", which isn't ours any more");
            return;
        }
        if (hit.optBoolean("destroyed")) {
            //saturation bombarded to nothing in the world (its copy decivilized): the colony is gone, as it'd be here
            MultiplayerLog.log().info("The world destroyed " + market.getName() + " (saturation bombardment)");
            DecivTracker.decivilize(market, hit.optBoolean("fullyDestroyed", true));
            return;
        }
        StringBuilder what = new StringBuilder();
        int unrest = hit.optInt("unrest", 0);
        if (unrest > 0) {
            RecentUnrest.get(market).add(unrest, hit.optString("reason", "Raid"));
            what.append(" -").append(unrest).append(" stability (").append(hit.optString("reason", "Raid")).append(")");
        }
        JSONObject disrupted = hit.optJSONObject("disrupted");
        if (disrupted != null) {
            for (Iterator<?> it = disrupted.keys(); it.hasNext(); ) {
                String id = (String) it.next();
                Industry industry = market.getIndustry(id);
                if (industry == null) continue;
                float days = (float) disrupted.getDouble(id);
                if (industry.getDisruptedDays() < days) industry.setDisrupted(days);
                what.append(", ").append(industry.getCurrentName()).append(" disrupted ").append(Math.round(days)).append(" days");
            }
        }
        if (hit.optBoolean("pollution") && !market.hasCondition(Conditions.POLLUTION)) {
            market.addCondition(Conditions.POLLUTION);
            what.append(", pollution");
        }
        int sizeLoss = hit.optInt("sizeLoss", 0);
        for (int i = 0; i < sizeLoss; i++) CoreImmigrationPluginImpl.reduceMarketSize(market); //as a saturation bombardment
        if (sizeLoss > 0) what.append(", size -").append(sizeLoss);
        MultiplayerLog.log().info("The world's raid hit " + market.getName() + ":" + what);
    }
}
