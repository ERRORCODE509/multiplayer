package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.rules.MemKeys;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.CharacterCreationData;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.rulecmd.newgame.NGCGetExerelinDefaults;
import com.fs.starfarer.api.impl.campaign.rulecmd.newgame.NGCSetPlayerFaction;
import com.fs.starfarer.api.impl.campaign.rulecmd.newgame.Nex_NGCFinalize;
import exerelin.campaign.ExerelinSetupData;
import exerelin.campaign.PlayerFactionStore;
import exerelin.campaign.SectorManager;
import matlabmaster.multiplayer.MultiplayerLog;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

/**
 * Nexerelin's new-game options, which shape the sector as much as the seed does (handcrafted or random core
 * worlds, the random sector's size, which factions are in it, their starting relations...): the server's world
 * keeps the ones it was made with, and a game made for a player's first login (client TitleJoin) is made with
 * them. Nexerelin keeps them in ExerelinSetupData's instance, which its dialog fills and its sector generation
 * reads.
 *
 * The only class that names Nexerelin's: only used when it's enabled (NewGameOptions), so the game never loads it
 * otherwise. Built against Nexerelin's jar (STATUS.md, Build).
 */
public class NexerelinNewGame {
    /** In the world's save (the sector's persistent data): its options, written as it's made. */
    private static final String KEY = "multiplayer_nexerelinOptions";

    /** onNewGame: the options this world was made with, kept in its save for the players' games made later. */
    public static void record() {
        try {
            JSONObject options = describe(ExerelinSetupData.getInstance());
            options.put("startFaction", PlayerFactionStore.getPlayerFactionIdNGC());
            options.put("freeStart", ExerelinSetupData.getInstance().freeStart);
            Global.getSector().getPersistentData().put(KEY, options.toString());
        } catch (JSONException | RuntimeException | LinkageError e) {
            MultiplayerLog.log().error("Couldn't keep this world's Nexerelin options in its save", e);
        }
    }

    /**
     * This world's options, for the players' new games: the ones it was made with, or, for a world made before they
     * were kept, the host's last ones (what Nexerelin's dialog saved, in the common folder) with the world's own
     * handcrafted-or-random choice.
     */
    public static JSONObject describeWorld() throws JSONException {
        Object recorded = Global.getSector().getPersistentData().get(KEY);
        if (recorded instanceof String) return new JSONObject((String) recorded);
        if (!NGCGetExerelinDefaults.loaded) {
            ExerelinSetupData.readFromFile(); //as Nexerelin's dialog does, the first time it opens
            NGCGetExerelinDefaults.loaded = true;
        }
        JSONObject options = describe(ExerelinSetupData.getInstance());
        SectorManager manager = SectorManager.getManager();
        if (manager != null) {
            options.put("corvusMode", manager.isCorvusMode());
            options.put("hardMode", manager.isHardMode());
            options.put("respawnFactions", manager.isRespawnFactions());
            options.put("onlyRespawnStartingFactions", manager.isOnlyRespawnStartingFactions());
        }
        MultiplayerLog.log().warn("This world was made before its Nexerelin options were kept in its save: players' new games use the host's last"
                + " new-game options (" + (options.optBoolean("corvusMode", true) ? "handcrafted" : "random") + " core worlds). If this world was"
                + " made with others, the players' sectors won't match it");
        return options;
    }

    private static JSONObject describe(ExerelinSetupData data) throws JSONException {
        JSONObject options = new JSONObject();
        options.put("numSystems", data.numSystems);
        options.put("numPlanets", data.numPlanets);
        options.put("numStations", data.numStations);
        options.put("maxPlanetsPerSystem", data.maxPlanetsPerSystem);
        options.put("maxMarketsPerSystem", data.maxMarketsPerSystem);
        options.put("randomColonies", data.randomColonies);
        options.put("randomColoniesMaxSize", data.randomColoniesMaxSize);
        options.put("procGenColonySizeOffset", data.procGenColonySizeOffset);
        JSONObject factions = new JSONObject();
        for (Map.Entry<String, Boolean> e : data.factions.entrySet()) factions.put(e.getKey(), Boolean.TRUE.equals(e.getValue()));
        options.put("factions", factions);
        options.put("corvusMode", data.corvusMode);
        options.put("respawnFactions", data.respawnFactions);
        options.put("onlyRespawnStartingFactions", data.onlyRespawnStartingFactions);
        options.put("startRelationsMode", data.startRelationsMode.name());
        options.put("applyStartRelationsModeToPirates", data.applyStartRelationsModeToPirates);
        options.put("homeworldPickMode", data.homeworldPickMode.name());
        options.put("homeworldAllowNeighbors", data.homeworldAllowNeighbors);
        options.put("easyMode", data.easyMode);
        options.put("hardMode", data.hardMode);
        options.put("prismMarketPresent", data.prismMarketPresent);
        options.put("randomAntiochEnabled", data.randomAntiochEnabled);
        options.put("spacerObligation", data.spacerObligation);
        options.put("useFactionWeights", data.useFactionWeights);
        options.put("randomFactionWeights", data.randomFactionWeights);
        options.put("randomStartLocation", data.randomStartLocation);
        options.put("numStartingOfficers", data.numStartingOfficers);
        options.put("numStartingOperatives", data.numStartingOperatives);
        options.put("dModLevel", data.dModLevel);
        options.put("enableStipend", data.enableStipend);
        options.put("enableCarePackage", data.enableCarePackage);
        if (data.startScenario != null) options.put("startScenario", data.startScenario);
        return options;
    }

    /**
     * A player's first login (the agent's NewGameMaker, the game thread): what Nexerelin's dialog would have set,
     * with the server's options (null: an older server that doesn't send them, Nexerelin's defaults). The player
     * starts on their own, unaligned and with no colony ("own faction", free start): in the handcrafted sector,
     * Nexerelin's default start (own faction, not free) would give them a colony of their own that isn't in the
     * world.
     */
    public static void apply(CharacterCreationData data, InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap, JSONObject options) {
        //what its dialog does as it opens: without it, its new-game scripts find their quest list missing
        new NGCGetExerelinDefaults().execute(null, dialog, new ArrayList<>(), memoryMap);
        //the server's options over Nexerelin's defaults, not over this player's own last choices
        ExerelinSetupData.resetInstance();
        ExerelinSetupData setup = ExerelinSetupData.getInstance();
        if (options == null) {
            MultiplayerLog.log().warn("The server didn't send its Nexerelin options (an older version): Nexerelin's defaults are used, and the sector may not match the server's");
        } else {
            set(setup, options);
        }
        //its dialog's memory keys and difficulty, from those
        NGCGetExerelinDefaults.loadMemoryKeysAndMisc(memoryMap.get(MemKeys.LOCAL));
        new NGCSetPlayerFaction().setFaction(Factions.PLAYER, dialog, memoryMap);
        setup.freeStart = true;
        if (options != null && !setup.corvusMode && (!Factions.PLAYER.equals(options.optString("startFaction", Factions.PLAYER)) || !options.optBoolean("freeStart", true))) {
            MultiplayerLog.log().warn("The server's random sector was made with another start (" + options.optString("startFaction") + ", free start "
                    + options.optBoolean("freeStart") + "), which Nexerelin's sector generation depends on: this sector may not match it");
        }
        //its last step: the chosen scenario, the starting fleet's d-mods
        new Nex_NGCFinalize().execute(null, dialog, new ArrayList<>(), memoryMap);
        MultiplayerLog.log().info("Nexerelin: the server's new-game options (" + (setup.corvusMode ? "handcrafted" : "random") + " core worlds), your own unaligned start");
    }

    private static void set(ExerelinSetupData setup, JSONObject o) {
        setup.numSystems = o.optInt("numSystems", setup.numSystems);
        setup.numPlanets = o.optInt("numPlanets", setup.numPlanets);
        setup.numStations = o.optInt("numStations", setup.numStations);
        setup.maxPlanetsPerSystem = o.optInt("maxPlanetsPerSystem", setup.maxPlanetsPerSystem);
        setup.maxMarketsPerSystem = o.optInt("maxMarketsPerSystem", setup.maxMarketsPerSystem);
        setup.randomColonies = o.optInt("randomColonies", setup.randomColonies);
        setup.randomColoniesMaxSize = o.optInt("randomColoniesMaxSize", setup.randomColoniesMaxSize);
        setup.procGenColonySizeOffset = o.optInt("procGenColonySizeOffset", setup.procGenColonySizeOffset);
        JSONObject factions = o.optJSONObject("factions");
        if (factions != null) {
            Iterator<?> ids = factions.keys();
            while (ids.hasNext()) {
                String id = (String) ids.next();
                setup.factions.put(id, factions.optBoolean(id, true));
            }
        }
        setup.corvusMode = o.optBoolean("corvusMode", setup.corvusMode);
        setup.respawnFactions = o.optBoolean("respawnFactions", setup.respawnFactions);
        setup.onlyRespawnStartingFactions = o.optBoolean("onlyRespawnStartingFactions", setup.onlyRespawnStartingFactions);
        try {
            setup.startRelationsMode = ExerelinSetupData.StartRelationsMode.valueOf(o.optString("startRelationsMode", setup.startRelationsMode.name()));
        } catch (IllegalArgumentException e) {
            MultiplayerLog.log().warn("Unknown Nexerelin start relations " + o.optString("startRelationsMode") + ": left at " + setup.startRelationsMode);
        }
        setup.applyStartRelationsModeToPirates = o.optBoolean("applyStartRelationsModeToPirates", setup.applyStartRelationsModeToPirates);
        try {
            setup.homeworldPickMode = ExerelinSetupData.HomeworldPickMode.valueOf(o.optString("homeworldPickMode", setup.homeworldPickMode.name()));
        } catch (IllegalArgumentException e) {
            MultiplayerLog.log().warn("Unknown Nexerelin homeworld mode " + o.optString("homeworldPickMode") + ": left at " + setup.homeworldPickMode);
        }
        setup.homeworldAllowNeighbors = o.optBoolean("homeworldAllowNeighbors", setup.homeworldAllowNeighbors);
        setup.easyMode = o.optBoolean("easyMode", setup.easyMode);
        setup.hardMode = o.optBoolean("hardMode", setup.hardMode);
        setup.prismMarketPresent = o.optBoolean("prismMarketPresent", setup.prismMarketPresent);
        setup.randomAntiochEnabled = o.optBoolean("randomAntiochEnabled", setup.randomAntiochEnabled);
        setup.spacerObligation = o.optBoolean("spacerObligation", setup.spacerObligation);
        setup.useFactionWeights = o.optBoolean("useFactionWeights", setup.useFactionWeights);
        setup.randomFactionWeights = o.optBoolean("randomFactionWeights", setup.randomFactionWeights);
        setup.randomStartLocation = o.optBoolean("randomStartLocation", setup.randomStartLocation);
        setup.numStartingOfficers = o.optInt("numStartingOfficers", setup.numStartingOfficers);
        setup.numStartingOperatives = o.optInt("numStartingOperatives", setup.numStartingOperatives);
        setup.dModLevel = o.optInt("dModLevel", setup.dModLevel);
        setup.enableStipend = o.optBoolean("enableStipend", setup.enableStipend);
        setup.enableCarePackage = o.optInt("enableCarePackage", setup.enableCarePackage);
        setup.startScenario = o.has("startScenario") ? o.optString("startScenario") : null;
    }
}
