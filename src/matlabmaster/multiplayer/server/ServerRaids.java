package matlabmaster.multiplayer.server;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.ColonyDecivListener;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.intel.group.FGAction;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.util.Misc;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.updates.RaidSync;
import matlabmaster.multiplayer.utils.ColonyMirrors;
import matlabmaster.multiplayer.utils.PlayerFactions;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The world's side of the raids on players' colonies (see RaidSync): runs the raids their games hand over, tells
 * them how each goes (the action it's on, how it ended), and passes on what the world did to their colonies' copies
 * (raids, bombardments: by these raids or any other of the world's), sent at once or kept until they're back.
 *
 * The raids are kept in the world's save (the sector's persistent data, raid id -> raid), with whose they are
 * ("raidOwner:<id>" in the PlayerRegistry), and how each ended ("raidOver:<id>"), so a player who asks about a
 * raid that's over (their game hands it over again before hearing) is told, not given a second one.
 */
public class ServerRaids {
    private static final String RAIDS_KEY = "multiplayer_raids";
    private static final float INTERVAL = 1f;

    private final Server server;
    private float timer = 0f;
    /** The action each raid was last on (raid id -> action id), to tell its owner when it changes. */
    private final Map<String, String> lastAction = new HashMap<>();
    /** What each copy of a player's colony looked like a second ago (market id -> RaidSync.colonyState). */
    private final Map<String, JSONObject> colonies = new HashMap<>();

    ServerRaids(Server server) {
        this.server = server;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, GenericRaidFGI> raids() {
        Map<String, Object> persistent = Global.getSector().getPersistentData();
        Object stored = persistent.get(RAIDS_KEY);
        if (!(stored instanceof HashMap)) {
            stored = new HashMap<String, GenericRaidFGI>();
            persistent.put(RAIDS_KEY, stored);
        }
        return (Map<String, GenericRaidFGI>) stored;
    }

    /** Game thread: a player's game hands over a raid on their colony (or again, not knowing it's here already). */
    void handOver(String clientId, JSONObject json) throws JSONException {
        String id = json.getString("id");
        String playerId = server.clientPlayers.get(clientId);
        if (playerId == null) return;
        GenericRaidFGI raid = raids().get(id);
        if (raid != null) { //already running: they only need to hear where it is
            lastAction.remove(id);
            return;
        }
        String over = server.registry.get("raidOver:" + id);
        if (over != null) {
            server.sendTo(clientId, ended(id, over).toString());
            return;
        }
        String faction = server.clientFactions.get(clientId);
        raid = RaidSync.create(json, PlayerFactions.isSlot(faction) ? faction : null);
        if (raid == null) {
            server.registry.put("raidOver:" + id, RaidSync.CANCELLED);
            server.sendTo(clientId, ended(id, RaidSync.CANCELLED).toString());
            return;
        }
        raids().put(id, raid);
        server.registry.put("raidOwner:" + id, playerId);
        MultiplayerLog.log().info(server.who(clientId) + " handed over a raid on their colony (" + id + ")");
    }

    /** Game thread: the player's game ended its raid (their crisis called it off): the world's goes home. */
    void callOff(String clientId, String id) {
        GenericRaidFGI raid = raids().get(id);
        String owner = server.registry.get("raidOwner:" + id);
        if (raid == null || owner == null || !owner.equals(server.clientPlayers.get(clientId))) return;
        if (!raid.isEnding() && !raid.isEnded()) raid.finish(false);
        MultiplayerLog.log().info(server.who(clientId) + " called off their raid " + id);
    }

    /**
     * A copy of a player's colony decivilized in the world (a saturation bombardment): their colony goes too. Heard
     * as it happens (it leaves the economy, so the colonies watch wouldn't see it).
     */
    private final ColonyDecivListener decivListener = new ColonyDecivListener() {
        @Override
        public void reportColonyAboutToBeDecivilized(MarketAPI market, boolean fullyDestroyed) {
            if (!ColonyMirrors.isMirror(market)) return;
            try {
                String owner = ColonyMirrors.ownerOf(market);
                MultiplayerLog.log().info(market.getName() + " (" + owner + ") was destroyed in the world");
                JSONObject hit = new JSONObject().put("market", market.getId()).put("destroyed", true).put("fullyDestroyed", fullyDestroyed);
                tell(owner, server.clientOf(owner), new JSONObject().put("commandId", "colonyHit").put("hit", hit));
            } catch (JSONException e) {
                MultiplayerLog.log().error("Couldn't tell " + market.getName() + "'s owner it was destroyed", e);
            }
        }

        @Override
        public void reportColonyDecivilized(MarketAPI market, boolean fullyDestroyed) {
        }
    };

    /** Hosting started (game thread). */
    void started() {
        if (!Global.getSector().getListenerManager().hasListener(decivListener)) {
            Global.getSector().getListenerManager().addListener(decivListener, true); //transient: never in the save
        }
    }

    /**
     * Game thread: a colony the player's game no longer has, which isn't theirs any more there (taken over: the
     * Knights of Ludd's takeover, say). The world's copy becomes that faction's market, as theirs is now.
     */
    void colonyLost(String clientId, String marketId, String faction) {
        MarketAPI mirror = ColonyMirrors.find(marketId);
        String owner = server.clientPlayers.get(clientId);
        if (mirror == null || owner == null || !owner.equals(ColonyMirrors.ownerOf(mirror))) return;
        if (Global.getSector().getFaction(faction) == null) return;
        ColonyMirrors.release(mirror, faction);
        colonies.remove(marketId);
        MultiplayerLog.log().info(server.who(clientId) + " lost " + mirror.getName() + " to " + faction + ": it's theirs in the world too");
    }

    /** Game thread, every frame (ServerScripts). */
    void advance(float amount) {
        timer += amount;
        if (timer < INTERVAL) return;
        timer = 0f;
        try {
            ColonyMirrors.keepOwnersNumbers();
            followRaids();
            watchColonies(true);
        } catch (Exception e) {
            MultiplayerLog.log().error("Couldn't follow the world's raids on players' colonies", e);
        }
    }

    private void followRaids() throws JSONException {
        for (Map.Entry<String, GenericRaidFGI> entry : new ArrayList<>(raids().entrySet())) {
            String id = entry.getKey();
            GenericRaidFGI raid = entry.getValue();
            String owner = server.registry.get("raidOwner:" + id);
            String ownerClient = server.clientOf(owner);
            if (raid.isEnding() || raid.isEnded()) {
                String outcome = raid.isAborted() ? RaidSync.ABORTED : RaidSync.FINISHED;
                raids().remove(id);
                lastAction.remove(id);
                server.registry.put("raidOver:" + id, outcome);
                MultiplayerLog.log().info("The " + raid.getBaseName() + " on " + RaidSync.target(raid).getName()
                        + (outcome.equals(RaidSync.ABORTED) ? " was defeated or called off" : " is over"));
                tell(owner, ownerClient, ended(id, outcome));
                continue;
            }
            makeHostileToOwner(raid, owner);
            FGAction action = raid.getCurrentAction();
            String actionId = action == null ? null : action.getId();
            if (ownerClient != null && actionId != null && !actionId.equals(lastAction.get(id))) {
                lastAction.put(id, actionId);
                JSONObject packet = new JSONObject().put("commandId", "raidAction").put("id", id).put("action", actionId);
                packet.put("payload", RaidSync.payloadState(raid)); //how its raiding went, for the owner's intel update
                server.sendTo(ownerClient, packet.toString());
            }
        }
    }

    /**
     * Its fleets are made hostile to the colony's owner (their faction here), never to this game's own player: a
     * subclass sets $makeHostile on them during the raid (the Diktat's), which here would be the host.
     */
    private void makeHostileToOwner(GenericRaidFGI raid, String owner) {
        if (!raid.getParams().makeFleetsHostile) return;
        String faction = owner == null ? null : server.registry.get("faction:" + owner);
        for (CampaignFleetAPI fleet : raid.getFleets()) {
            MemoryAPI memory = fleet.getMemoryWithoutUpdate();
            if (memory.contains(MemFlags.MEMORY_KEY_MAKE_HOSTILE)) {
                Misc.clearFlag(memory, MemFlags.MEMORY_KEY_MAKE_HOSTILE);
                memory.unset(MemFlags.MEMORY_KEY_MAKE_HOSTILE);
            }
            if (faction != null && !Misc.isFleetMadeHostileToFaction(fleet, faction)) Misc.makeHostileToFaction(fleet, faction, -1);
        }
    }

    /**
     * What the world did to players' colonies' copies since a second ago, for their owners. Also just before and
     * after a player's colonies update them (ServerFactionSync), report false the second time: what changes then is
     * the owner's (a smaller colony), not the world's doing.
     */
    void watchColonies(boolean report) throws JSONException {
        Set<String> seen = new HashSet<>();
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (!ColonyMirrors.isMirror(market)) continue;
            seen.add(market.getId());
            JSONObject now = RaidSync.colonyState(market);
            JSONObject before = colonies.put(market.getId(), now);
            if (before == null || !report) continue; //new here: nothing to compare with
            JSONObject hit = RaidSync.hit(market, before, now);
            if (hit == null) continue;
            String owner = ColonyMirrors.ownerOf(market);
            MultiplayerLog.log().info(market.getName() + " (" + owner + ") was hit: " + hit);
            tell(owner, server.clientOf(owner), new JSONObject().put("commandId", "colonyHit").put("hit", hit));
        }
        colonies.keySet().retainAll(seen);
    }

    /** To a player's game now, or when they're back. */
    private void tell(String playerId, String clientId, JSONObject packet) {
        if (playerId == null) return;
        if (clientId != null) server.sendTo(clientId, packet.toString());
        else server.registry.queue("raids", playerId, packet);
    }

    private static JSONObject ended(String id, String outcome) throws JSONException {
        return new JSONObject().put("commandId", "raidEnded").put("id", id).put("outcome", outcome);
    }

    /** A player is back (network thread): the news of their raids and colonies while they were away. */
    void joined(String clientId, String playerId) {
        JSONArray news = server.registry.take("raids", playerId);
        for (int i = 0; i < news.length(); i++) {
            try {
                server.sendTo(clientId, news.getJSONObject(i).toString());
            } catch (JSONException e) {
                MultiplayerLog.log().error("Couldn't send a raid's news to " + clientId, e);
            }
        }
        if (news.length() > 0) MultiplayerLog.log().info("Sent " + news.length() + " raid reports from while they were away to " + server.who(clientId));
    }

    /** Forgets what it saw of the last world (hosting stopped, or another game). */
    void stopped() {
        Global.getSector().getListenerManager().removeListener(decivListener);
        lastAction.clear();
        colonies.clear();
        timer = 0f;
    }
}
