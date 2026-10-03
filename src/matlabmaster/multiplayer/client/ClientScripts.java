package matlabmaster.multiplayer.client;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.campaign.Faction;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.updates.BattleSync;
import matlabmaster.multiplayer.updates.DebrisSync;
import matlabmaster.multiplayer.updates.EntitySync;
import matlabmaster.multiplayer.updates.WorldBounties;
import matlabmaster.multiplayer.updates.WorldMarkets;
import matlabmaster.multiplayer.updates.WorldOwnership;
import matlabmaster.multiplayer.updates.FleetSync;
import matlabmaster.multiplayer.updates.WorldSync;
import matlabmaster.multiplayer.utils.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ClientScripts implements EveryFrameScript {
    private final Client client;
    private float timer = 0f;
    private final FleetSync fleetSync = new FleetSync();
    private final WorldSync worldSync = new WorldSync();
    private final SectorScriptsUtility sectorScriptsUtility = new SectorScriptsUtility();
    /** Orbits drift apart frame by frame on each machine (they don't follow the clock): re-synced this often. */
    private static final float ORBIT_RESYNC_SECONDS = 10f;
    private float orbitTimer = 0f;
    private final ClientMarkets markets = new ClientMarkets();
    /** Keeps our fleet by what we're talking to while the world moves on (see InteractionOrbit). */
    private final InteractionOrbit interactionOrbit = new InteractionOrbit();
    /** The raids on our colonies, which the world runs (see RaidSync). */
    private final OwnRaids ownRaids = new OwnRaids();
    /** The world's salvageable things: what's gone from the world goes here, what we salvage goes there. */
    private final WorldEntities worldEntities = new WorldEntities();
    /** The world's open bounties, in our intel tab while connected. */
    private final BountyBoard bountyBoard = new BountyBoard();
    /** Whether this session made copies of the world's markets (WorldMarkets), to remove on leaving. */
    private boolean worldMarketCopies = false;
    /** Our reputation and faction as last sent to the server (null: not since joining), see sendOwnFaction. */
    private String factionSent = null;
    /** The battle debris fields the server knows of (ours sent, or others' received), see syncDebris. */
    private final Set<String> debrisKnown = new HashSet<>();
    /** Our reputation as last sent, to log what changes. */
    private JSONObject reputationSent = null;
    private float factionTimer = 0f;
    /** Our colonies as last sent to the server (null: not since joining), see sendOwnColonies. */
    private String coloniesSent = null;
    private float coloniesTimer = 0f;
    private static final float COLONIES_INTERVAL = 5f;
    /** The clock corrections not made up for in our colonies' construction yet (game days), see handleServerTime. */
    private float clockCorrectedDays = 0f;
    /** Whether this game has mirrors of other players' colonies, from the server: removed on leaving it. */
    private boolean hasMirrors = false;

    // Message waitlist coming from client thread
    private static final ConcurrentLinkedQueue<JSONObject> messageQueue = new ConcurrentLinkedQueue<>();

    public ClientScripts(Client client) {
        this.client = client;

        // Attaching listener to fill the queue
        this.client.addListener(new Client.ClientListener() {  // CHANGED FROM setListener TO addListener
            @Override
            public void onDisconnected() {
                messageQueue.clear();
                MultiplayerLog.log().info("CLEARING MESSAGE QUEUE.");
            }

            @Override
            public void onMessageReceived(String msg) {
                try {
                    // Transform msg to JSON and add it to the queue
                    messageQueue.add(new JSONObject(msg));
                } catch (Exception e) {
                    MultiplayerLog.log().error("JSON FORMAT ERROR: " + e.getMessage());
                }
            }
        });
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return true;
    }

    @Override
    public void advance(float amount) {
        interactionOrbit.advance(); //connected or not: an orbit of ours lets go when the player moves
        PositionSmoothing.advance(amount); //fleet copies' corrections, in every game (the server's too)
        if (client == null || !client.isConnected()) {
            //put back any sector scripts taken out while we were not the authority, so they are not lost
            sectorScriptsUtility.restoreScripts();
            if (factionSent != null) { //just disconnected: nobody else is here any more
                PlayerFactions.hideAll();
                int copies = FleetHelper.removePlayerCopies();
                if (copies > 0) MultiplayerLog.log().info("Removed the other players' fleets (" + copies + ")");
                notify("Disconnected from the multiplayer server");
            }
            factionSent = null;
            coloniesSent = null;
            debrisKnown.clear();
            CopyAI.removeAll();
            FleetFlags.ownFaction = null;
            ownRaids.reset();
            worldEntities.reset();
            WorldOwnership.reset();
            bountyBoard.reset();
            if (worldMarketCopies) {
                int copies = WorldMarkets.removeAll(); //the world's, not this save's
                if (copies > 0) MultiplayerLog.log().info("Removed the copies of " + copies + " of the world's markets");
                worldMarketCopies = false;
            }
            if (client != null && client.wasPaused) { //left in a dialog: never told the server, nobody to tell now
                client.wasPaused = false;
                PauseUtility.clearPausedName();
            }
            if (hasMirrors) {
                ColonyMirrors.removeAll(); //the world's colonies stay in the world, not in this save
                hasMirrors = false;
            }
            return;
        }
        //finish joining here, on the game thread, before any received message is processed
        if (client.isJoinPending()) {
            client.completeJoin();
            if (!client.isConnected()) return;
            factionSent = null; //a new server knows nothing of us yet
            coloniesSent = null;
            debrisKnown.clear();
            CopyAI.removeAll();
            ownRaids.reset();
            worldEntities.reset();
        }
        factionTimer += amount;
        if (factionSent == null || factionTimer >= PlayerFactions.RELATIONS_INTERVAL) {
            factionTimer = 0f;
            sendOwnFaction();
            if (!client.isSelfHosted) {
                CopyAI.installAround(Global.getSector().getPlayerFleet()); //the world's fleets decide as vanilla's
                bountyBoard.keep();
                syncDebris();
                removeDuplicateCopies();
            }
        }
        coloniesTimer += amount;
        if (coloniesSent == null || coloniesTimer >= COLONIES_INTERVAL) {
            coloniesTimer = 0f;
            sendOwnColonies();
        }
        //our crises' raids go to the world (the host's game is the world: they're there already)
        if (!client.isSelfHosted) ownRaids.advance(amount, client);

        //handle the game pausing , disable classic in game pause
        //if the game is in a dialog inform the server
        PauseUtility.clientPauseUtility(client, fleetSync, markets, interactionOrbit);
        // --- 1. process received message every frame ---
        // they are processed every frame to limit lag
        while (!messageQueue.isEmpty()) {
            JSONObject json = messageQueue.poll();
            if (json != null) {
                processMessage(json);
            }
        }

        // --- 2. keep the orbits where we are in step with the server's ---
        if (!client.isSelfHosted) {
            orbitTimer += amount;
            if (orbitTimer >= ORBIT_RESYNC_SECONDS) {
                orbitTimer = 0f;
                try {
                    WorldSync.requestOrbitSnapshotForLocation(Global.getSector().getPlayerFleet().getContainingLocation(), client);
                } catch (Exception e) {
                    MultiplayerLog.log().warn("Couldn't ask for the orbits: " + e.getMessage());
                }
            }
        }

        // --- 3. send updates (TICKS 20 TPS) ---
        timer += amount;
        float INTERVAL = 0.05f;
        if (timer >= INTERVAL) {
            timer -= INTERVAL;
            executeTick();
        }
    }

    /**
     * Puts the sector scripts back before the game is saved, so a save made while connected keeps them.
     * If this client is still not the authority, the next tick takes them out again.
     */
    public void restoreSectorScripts() {
        sectorScriptsUtility.restoreScripts();
        CopyAI.removeAll(); //never in a save: put back within a second
        bountyBoard.removeAll(); //the same
    }

    /** A new game was loaded: any scripts saved from the previous game belong to a sector that is gone. */
    public void onGameLoad() {
        sectorScriptsUtility.forgetScripts();
        interactionOrbit.forget();
        ownRaids.reset();
        worldEntities.reset();
        bountyBoard.forget();
        WorldOwnership.reset();
        //a save made during a session has the copies of the world's markets: the next session makes them again
        int marketCopies = WorldMarkets.removeAll();
        if (marketCopies > 0) MultiplayerLog.log().info("Removed " + marketCopies + " copies of the world's markets saved with this game");
        ownColonyIds = null; //another game's colonies
        PauseUtility.clearPausedName(); //a save from before this was fixed may have it
        PositionSmoothing.clear();
        //a save made during a session has the other players' fleets as they were then; they come back on joining
        int copies = FleetHelper.removePlayerCopies();
        if (copies > 0) MultiplayerLog.log().info("Removed " + copies + " other players' fleets saved with this game");
        //our battles against the world's NPC fleets: the server's must lose the same ships (transient: not in the save)
        Global.getSector().addTransientListener(new BaseCampaignEventListener(false) {
            @Override
            public void reportBattleFinished(CampaignFleetAPI primaryWinner, BattleAPI battle) {
                if (!client.isConnected() || client.isSelfHosted || battle == null || !battle.isPlayerInvolved()) return;
                try {
                    WorldBounties.paySystemBounties(bountyBoard.list(), primaryWinner, battle); //the world's, near here
                } catch (Exception e) {
                    MultiplayerLog.log().error("Couldn't pay the world's system bounties", e);
                }
                try {
                    JSONObject result = BattleSync.describe(battle);
                    if (result.length() == 0) return;
                    JSONObject packet = new JSONObject();
                    packet.put("commandId", "battleResult");
                    packet.put("result", result);
                    client.send(packet.toString());
                    MultiplayerLog.log().info("Sent the result of our battle (" + result.length() + " NPC fleets) to the server");
                } catch (Exception e) {
                    MultiplayerLog.log().error("Couldn't send our battle's result to the server", e);
                }
            }
        });
    }

    /**
     * Incoming message dispatcher.
     * Executed in the main game thread
     */
    private void processMessage(JSONObject message) {
        try {
            if (!message.has("commandId")) return;
            String commandId = message.getString("commandId");
            JSONObject packet;
            switch (commandId) {
                case "playerFleetUpdate":
                    if(Global.getSector().getEntityById(message.getString("fleetId")) instanceof CampaignFleetAPI){
                        fleetSync.handleRemoteFleetUpdate(message);
                    }else{
                        //only run if not paused because if the client is pause it will continuously ask for snapshots
                        //and then try to spawn all of them when unpausing resulting in 1000 fleet spawning
                        if(!Global.getSector().isPaused() && askFor(message.getString("fleetId"))){
                            //usually called when a fleet dies and respawn
                            MultiplayerLog.log().error("playerFleetUpdate : unknown fleet");
                            packet = new JSONObject();
                            packet.put("commandId","requestPlayerFleetSnapshot");
                            packet.put("to",message.getString("from"));
                            packet.put("from",client.clientId);
                            client.send(packet.toString());
                        }
                    }
                    break;
                case "handleAllFleetsSnapshot":
                    int i;
                    for(i = 0; i < message.getJSONArray("fleets").length() ; i ++){
                        JSONObject unserializedFleet = (JSONObject) message.getJSONArray("fleets").get(i);
                        if (isOwnFleet(unserializedFleet.getString("id"))) continue; //the server's copy of our own fleet
                        if(Global.getSector().getEntityById(unserializedFleet.getString("id")) instanceof CampaignFleetAPI){
                            ((CampaignFleetAPI) Global.getSector().getEntityById(unserializedFleet.getString("id"))).despawn();
                        }
                        FleetSerializer.unSerializeFleet(unserializedFleet,Global.getFactory().createEmptyFleet(Faction.NO_FACTION, true));
                    }
                    MultiplayerLog.log().info("added " + i + " fleets");
                    break;
                case "fleetSnapshot":
                    spawnCopy(message.getJSONObject("fleet"));
                    break;
                case "playerLeft": {
                    FleetHelper.removeFleetById(message.getString("id"));
                    String name = client.players.remove(message.getString("id"));
                    if (name != null) notify(name + " left the game");
                    client.showPlayers();
                    MultiplayerLog.log().info("[LEFT] " + message.optString("name", message.getString("id")) + " left the game");
                    break;
                }
                case "players": {
                    //who's here, on joining (us too); the window lists them
                    JSONArray players = message.getJSONArray("players");
                    client.players.clear();
                    for (int p = 0; p < players.length(); p++) {
                        client.players.put(players.getJSONObject(p).getString("id"), players.getJSONObject(p).getString("name"));
                    }
                    client.showPlayers();
                    List<String> others = new ArrayList<>();
                    for (Map.Entry<String, String> player : client.players.entrySet()) {
                        if (!player.getKey().equals(client.clientId)) others.add(player.getValue());
                    }
                    notify(others.isEmpty() ? "Joined the server: nobody else is here yet" : "Joined the server with " + String.join(", ", others));
                    break;
                }
                case "playerJoined":
                    client.players.put(message.getString("id"), message.optString("name", message.getString("id")));
                    client.showPlayers();
                    notify(message.optString("name", "A player") + " joined the game");
                    MultiplayerLog.log().info("[JOINED] " + message.optString("name", message.getString("id")) + " joined the game");
                    break;
                case "debrisFields": {
                    //debris another player's battle left
                    JSONObject fields = message.getJSONObject("fields");
                    for (Iterator<?> it = fields.keys(); it.hasNext(); ) {
                        String id = (String) it.next();
                        if (!client.isSelfHosted) DebrisSync.apply(id, fields.getJSONObject(id));
                        debrisKnown.add(id);
                    }
                    break;
                }
                case "debrisAll": {
                    //every battle debris field in the world, on joining: the ones made while we were away come, and
                    //any we got from a server before that the world doesn't have any more go
                    if (client.isSelfHosted) break; //our game is the world
                    JSONObject fields = message.getJSONObject("fields");
                    Set<String> ids = new HashSet<>();
                    for (Iterator<?> it = fields.keys(); it.hasNext(); ) ids.add((String) it.next());
                    int removed = DebrisSync.removeReceivedExcept(ids);
                    for (String id : ids) DebrisSync.apply(id, fields.getJSONObject(id));
                    debrisKnown.addAll(ids);
                    MultiplayerLog.log().info("The world has " + ids.size() + " battle debris fields" + (removed > 0 ? "; removed " + removed + " gone since we were last here" : ""));
                    break;
                }
                case "loginRefused":
                    //wrong password, a username taken, ...: the server closes the connection
                    MultiplayerLog.log().error("The server refused the login: " + message.optString("reason"));
                    notify("Login refused: " + message.optString("reason"));
                    break;
                case "worldBounties":
                    if (!client.isSelfHosted) bountyBoard.listed(message.getJSONArray("bounties"));
                    break;
                case "bountyReward":
                    //we beat a world bounty's target: paid as vanilla pays
                    if (!client.isSelfHosted) WorldBounties.reward(message.getJSONObject("reward"));
                    break;
                case "worldOwnership":
                    //who owns the world's markets and objectives (all of them on joining, then what changed)
                    if (!client.isSelfHosted) {
                        for (String id : WorldOwnership.apply(message.getJSONObject("state"))) {
                            client.send(new JSONObject().put("commandId", "requestWorldMarket").put("id", id).toString());
                        }
                    }
                    break;
                case "worldMarket":
                    //a market the world founded that this game doesn't have: a copy, while connected
                    if (!client.isSelfHosted) {
                        WorldMarkets.create(message.getJSONObject("market"));
                        worldMarketCopies = true;
                    }
                    break;
                case "worldEntities":
                    if (!client.isSelfHosted) worldEntities.listed(client, message.getString("location"), message.getJSONArray("ids"));
                    break;
                case "worldEntityGone":
                    if (!client.isSelfHosted) worldEntities.gone(java.util.Collections.singletonList(message.getString("id")));
                    break;
                case "worldEntitiesGone":
                    if (!client.isSelfHosted) worldEntities.gone(EntitySync.fromJson(message.getJSONArray("ids")));
                    break;
                case "colonyHit":
                case "raidAction":
                case "raidEnded":
                    //the world's raids on our colonies: what they did, how they're going, how they ended
                    if (!client.isSelfHosted) OwnRaids.received(message);
                    break;
                case "debrisGone":
                    debrisKnown.remove(message.getString("id"));
                    if (!client.isSelfHosted) DebrisSync.remove(message.getString("id"));
                    break;
                case "playerRenamed":
                    client.players.put(message.getString("id"), message.getString("name"));
                    client.showPlayers();
                    MultiplayerLog.log().info(message.optString("before") + " is now " + message.getString("name"));
                    break;
                case "globalFleetsUpdate":
                    //modifying msg to match what fleetSync.handleRemoteFleetUpdate expect
                    JSONObject updates = message.getJSONObject("updates");
                    Iterator<?> keys = updates.keys();
                    while (keys.hasNext()){
                        String fleetId = (String) keys.next();
                        try {
                            applyGlobalFleetChange(fleetId, updates.getJSONObject(fleetId));
                        } catch (Exception e) {
                            //one fleet that can't be applied (e.g. in a location this game doesn't have) must not drop the rest
                            MultiplayerLog.log().error("globalFleetsUpdate : couldn't apply the change to " + fleetId + ": " + e.getMessage(), e);
                        }
                    }
                    break;
                case "worldPaused":
                case "worldResumed":
                    //the server's game is the world; in "host current game" mode it stops while the host is in a dialog
                    boolean worldPaused = "worldPaused".equals(commandId);
                    MultiplayerLog.log().info(worldPaused ? "The host paused the world (dialog or menu): NPC fleets wait" : "The world runs again");
                    if (client.ui != null) client.ui.setWorldPaused(worldPaused);
                    break;
                case "handleFleetSnapshotRequest":
                    if (spawnCopy(message.getJSONObject("fleet"))) {
                        MultiplayerLog.log().info("spawned "+ message.getJSONObject("fleet").getString("id")+ " fleet following request");
                    }
                    break;
                case "requestPlayerFleetSnapshot":
                    packet = new JSONObject();
                    packet.put("commandId","handleFleetSnapshotRequest");
                    packet.put("to",message.getString("from"));
                    packet.put("fleet",FleetSerializer.serializeFleet(Global.getSector().getPlayerFleet()));
                    client.send(packet.toString());
                    break;
                case "handleOrbitSnapshotForLocation":
                    JSONObject orbits = message.getJSONObject("orbits");
                    Iterator<?> orbitKeys = orbits.keys();
                    while (orbitKeys.hasNext()) {
                        String key = (String) orbitKeys.next();
                        try {
                            WorldSerializer.unSerializeOrbit(orbits.getJSONObject(key));
                        } catch (Exception e) {
                            MultiplayerLog.log().warn("Failed to apply orbit for " + key + ": " + e.getMessage());
                        }
                    }
                    break;
                case "handleServerTime":
                    if (!client.isSelfHosted) { //the host's own game is the server's clock
                        long corrected = ClockUtility.syncToServer(Global.getSector().getClock(), message.getLong("timestamp"));
                        boolean jumped = Math.abs(corrected) > ClockUtility.MAX_DRIFT_MS;
                        //not while we're paused (a dialog, a battle): our clock stops, the world's doesn't, and it'd say so every second
                        if (jumped && !Global.getSector().isPaused()) MultiplayerLog.log().info("Clock set to the server's (it was " + (corrected / 3600000f) + " game hours off)");
                        //behind the world (it fast-forwarded, or we ran slow): our colonies' construction catches up
                        //too, the nudges once they add up to a few game hours
                        clockCorrectedDays += corrected / 86400000f;
                        if (jumped || clockCorrectedDays >= 0.1f) {
                            if (clockCorrectedDays > 0) ColonyMirrors.catchUpConstruction(clockCorrectedDays);
                            clockCorrectedDays = 0f;
                        }
                    }
                    client.ui.setServerTime(message.getLong("timestamp"));
                    break;
                case "factionRelations":
                    //the world's relations, and the other players' reputations (never ours: this game decides it)
                    if (!client.isSelfHosted) PlayerFactions.applyWorldRelations(message.getJSONObject("relations"));
                    break;
                case "playerFactionLook":
                    //how another player's faction looks (their own, or unaligned), listed in the intel tab while
                    //they're connected; never our own (here, we're the player)
                    if (!client.isSelfHosted) {
                        String faction = message.getString("faction");
                        PlayerFactions.applyLook(faction, message.optJSONObject("look"));
                        PlayerFactions.setShown(faction, message.optBoolean("inUse", true) && !faction.equals(client.faction));
                    }
                    break;
                case "yourFaction":
                    //our faction in the other games, from the server's reply to our hello
                    client.faction = message.getString("faction");
                    if (!client.isSelfHosted) FleetFlags.ownFaction = client.faction; //the world's fleets made hostile to it are hostile to us
                    break;
                case "playerColonies":
                    //another player's colonies (they run in their game): mirrored here. The host's game is the world:
                    //the server already mirrors them there
                    if (!client.isSelfHosted && !message.getString("player").equals(PlayerIdentity.id())) {
                        ColonyMirrors.apply(message.getString("player"), message.getString("faction"), message.getJSONArray("colonies"));
                        hasMirrors = true;
                    }
                    break;
                case "intercepted": {
                    //a fleet the server's AI chased caught us: the encounter is ours to open, as vanilla does
                    SectorEntityToken fleet = Global.getSector().getEntityById(message.getString("fleetId"));
                    CampaignFleetAPI own = Global.getSector().getPlayerFleet();
                    boolean opened = false;
                    if (fleet instanceof CampaignFleetAPI && own != null && !own.isInHyperspaceTransition()
                            && !Global.getSector().getCampaignUI().isShowingDialog()) {
                        opened = Global.getSector().getCampaignUI().showInteractionDialog(fleet);
                    }
                    if (!opened) { //the server holds us both until we answer: let go
                        JSONObject release = new JSONObject();
                        release.put("commandId", "unpaused");
                        client.send(release.toString());
                    }
                    break;
                }
                case "colonyStockRequest":
                    //someone opened one of our colonies' market: its stock is ours to give
                    markets.colonyStockRequest(client, message.getString("marketId"));
                    break;
                case "colonyTrade":
                    //someone traded at one of our colonies
                    markets.colonyTrade(message.getJSONObject("trade"));
                    break;
                case "marketSnapshot":
                    markets.snapshot(message.getJSONObject("snapshot"));
                    break;
                default:
                    MultiplayerLog.log().warn("unknown command: " + commandId);
                    break;
            }
        } catch (Exception e) {
            MultiplayerLog.log().error("Exception in processMessage: " + e.getMessage() + " " + Arrays.toString(e.getStackTrace()) + " " + message.toString(), e);
        }
    }

    /** One fleet's entry from a "globalFleetsUpdate": a whole fleet ADDED or REMOVED, or a diff to apply. */
    private void applyGlobalFleetChange(String fleetId, JSONObject change) throws Exception {
        SectorEntityToken existing = Global.getSector().getEntityById(fleetId);
        //a whole fleet appeared or disappeared on the authority's side (these used to fall
        //through to applyFleetDiff, which ignores them: gone fleets lingered forever)
        if (change.has("action")) {
            String action = change.getString("action");
            if ("REMOVED".equals(action)) {
                if (existing instanceof CampaignFleetAPI && existing != Global.getSector().getPlayerFleet()
                        && existing.getContainingLocation() != null) {
                    //jumped out of sight: the jump's flash where it was, as other players' fleets have
                    if (change.optBoolean("jumped")) FleetSerializer.jumpFlash((CampaignFleetAPI) existing, existing.getContainingLocation(), existing.getLocation());
                    existing.getContainingLocation().removeEntity(existing);
                }
            } else if ("ADDED".equals(action) && !(existing instanceof CampaignFleetAPI)) {
                //the full fleet is in the message: no need to ask for a snapshot
                CampaignFleetAPI copy = Global.getFactory().createEmptyFleet(Faction.NO_FACTION, true);
                FleetSerializer.unSerializeFleet(change.getJSONObject("value"), copy);
                CopyAI.install(copy);
                if (change.optBoolean("jumped") && copy.getContainingLocation() != null) FleetSerializer.jumpFlash(copy, copy.getContainingLocation(), copy.getLocation());
            }
            return;
        }
        JSONObject updateWrapper = new JSONObject();
        updateWrapper.put("fleetId", fleetId);
        updateWrapper.put("changes", change);

        //same as PLayerFleetUpdate but with a list of fleets to update
        if (existing instanceof CampaignFleetAPI) {
            fleetSync.handleRemoteFleetUpdate(updateWrapper);
        } else {
            if(!Global.getSector().isPaused() && askFor(fleetId)){
                //only run if not paused because if the client is pause it will continuously ask for snapshots
                //and then try to spawn all of them when unpausing resulting in 1000 fleet spawning
                JSONObject packet = new JSONObject();
                packet.put("commandId","requestFleetSnapshot");
                packet.put("fleetId",fleetId);
                MultiplayerLog.log().warn("globalFleetsUpdate : unknown fleet " + fleetId);
                client.send(packet.toString());
            }
        }
    }

    /**
     * Our reputation (this game is the only authority on it) and how our faction looks, to the server, which gives
     * them to our faction there: NPC fleets in the world treat us as this save says. Sent when they change.
     */
    private void sendOwnFaction() {
        try {
            JSONObject packet = new JSONObject();
            packet.put("commandId", "playerFaction");
            packet.put("name", PlayerIdentity.name()); //who we are to the others: our character, whatever they're called now
            packet.putOpt("look", PlayerFactions.describeOwnFaction());
            JSONObject reputation = PlayerFactions.ownReputation();
            packet.put("reputation", reputation);
            packet.put("blueprints", PlayerFactions.ownBlueprints()); //our colonies' fleets in the world are built from them
            String text = packet.toString();
            if (text.equals(factionSent)) return;
            client.send(text);
            factionSent = text;
            //our own name in the window's list (the server tells only the others when we're renamed)
            if (client.players.containsKey(client.clientId) && !PlayerIdentity.name().equals(client.players.put(client.clientId, PlayerIdentity.name()))) {
                client.showPlayers();
            }
            if (reputationSent != null) { //what changed since the last time (on joining it's all of it)
                double highest = 0;
                for (Iterator<?> it = reputation.keys(); it.hasNext(); ) {
                    String id = (String) it.next();
                    double before = reputationSent.optDouble(id, 0), now = reputation.getDouble(id);
                    if (Math.abs(now - before) < 0.005) continue;
                    MultiplayerLog.log().info("Our reputation with " + id + ": " + before + " -> " + now + " (sent to the server)");
                    if (Math.abs(now - before) > Math.abs(highest)) highest = now - before;
                }
                //vanilla's sound for it comes from CoreScript, a sector script, which is off while connected
                if (highest != 0) Global.getSoundPlayer().playUISound(highest > 0 ? "ui_rep_raise" : "ui_rep_drop", 1f, 1f);
            }
            reputationSent = reputation;
        } catch (Exception e) {
            MultiplayerLog.log().error("Couldn't send our reputation to the server", e);
        }
    }

    /**
     * Battle debris fields (see DebrisSync): any new one around us that our battles left goes to the server, and
     * any we know of that's gone here (we salvaged it, or it ran out) goes everywhere.
     */
    private void syncDebris() {
        try {
            CampaignFleetAPI own = Global.getSector().getPlayerFleet();
            if (own == null || own.getContainingLocation() == null) return;
            JSONObject fields = DebrisSync.battleFields(own.getContainingLocation(), true);
            JSONObject fresh = new JSONObject();
            for (Iterator<?> it = fields.keys(); it.hasNext(); ) {
                String id = (String) it.next();
                if (debrisKnown.add(id)) {
                    fresh.put(id, fields.get(id));
                    DebrisSync.changed(id, fields.getJSONObject(id)); //as sent
                }
            }
            //a shared field (ours or the world's) a battle of ours just added to, or we recovered ships from: the others get it again
            JSONObject all = DebrisSync.battleFields(own.getContainingLocation(), false);
            for (Iterator<?> it = all.keys(); it.hasNext(); ) {
                String id = (String) it.next();
                if (!fresh.has(id) && debrisKnown.contains(id) && DebrisSync.changed(id, all.getJSONObject(id))) fresh.put(id, all.get(id));
            }
            if (fresh.length() > 0) {
                JSONObject packet = new JSONObject();
                packet.put("commandId", "debrisFields");
                packet.put("fields", fresh);
                client.send(packet.toString());
                for (Iterator<?> it = fresh.keys(); it.hasNext(); ) DebrisSync.shared((String) it.next());
            }
            for (Iterator<String> it = debrisKnown.iterator(); it.hasNext(); ) {
                String id = it.next();
                if (DebrisSync.exists(id)) continue;
                it.remove();
                JSONObject packet = new JSONObject();
                packet.put("commandId", "debrisGone");
                packet.put("id", id);
                client.send(packet.toString());
            }
        } catch (Exception e) {
            MultiplayerLog.log().error("Couldn't sync the debris fields", e);
        }
    }

    /**
     * Our colonies (they run in this game, the only authority on them), to the server, which mirrors them in the
     * world for the other players and keeps them there while we're offline. Sent when they change.
     */
    private void sendOwnColonies() {
        try {
            JSONObject packet = new JSONObject();
            packet.put("commandId", "colonies");
            JSONArray colonies = ColonyMirrors.describeOwnColonies();
            sendLostColonies(colonies);
            packet.put("colonies", colonies);
            String text = packet.toString();
            if (text.equals(coloniesSent)) return;
            boolean joined = coloniesSent == null;
            client.send(text);
            coloniesSent = text;
            //just joined: their stock too, for visitors while we're away (after the colonies: the server mirrors those first)
            if (joined) markets.sendAllColonyStock(client);
        } catch (Exception e) {
            MultiplayerLog.log().error("Couldn't send our colonies to the server", e);
        }
    }

    /** Our colonies' ids as last described, to tell which we've lost since (see sendLostColonies); null: none yet. */
    private Set<String> ownColonyIds;

    /**
     * Colonies we had and no longer have because another faction has them now (taken over: the Knights of Ludd,
     * say): the world's copy becomes theirs too. Sent before the colonies, which would otherwise just remove it.
     */
    private void sendLostColonies(JSONArray colonies) throws org.json.JSONException {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < colonies.length(); i++) ids.add(colonies.getJSONObject(i).getString("id"));
        if (ownColonyIds != null) {
            for (String id : ownColonyIds) {
                if (ids.contains(id)) continue;
                com.fs.starfarer.api.campaign.econ.MarketAPI market = Global.getSector().getEconomy().getMarket(id);
                if (market == null || market.isPlayerOwned() || market.isPlanetConditionMarketOnly() || market.getFaction() == null) continue;
                if (market.getFaction().isPlayerFaction() || market.getFaction().isNeutralFaction()) continue;
                client.send(new JSONObject().put("commandId", "colonyLost").put("id", id).put("faction", market.getFactionId()).toString());
                MultiplayerLog.log().info("We lost " + market.getName() + " to " + market.getFaction().getDisplayName());
            }
        }
        ownColonyIds = ids;
    }

    /** A line in the campaign's message log (bottom left), for what the player should notice without the window. */
    private static void notify(String text) {
        if (Global.getSector().getCampaignUI() != null) Global.getSector().getCampaignUI().addMessage(text, Misc.getHighlightColor());
    }

    /** Fleets asked for in full (fleet id -> when, ms), see askFor. */
    private final Map<String, Long> askedFor = new HashMap<>();
    private static final long ASK_AGAIN_MS = 2000;

    /**
     * Whether to ask the server for a fleet this game doesn't have: not again while the answer is on its way. Every
     * update of that fleet (20 a second) used to ask, and each answer spawned a copy.
     */
    private boolean askFor(String fleetId) {
        long now = System.currentTimeMillis();
        Long asked = askedFor.get(fleetId);
        if (asked != null && now - asked < ASK_AGAIN_MS) return false;
        if (askedFor.size() > 500) askedFor.clear(); //only recent ones matter
        askedFor.put(fleetId, now);
        return true;
    }

    /**
     * A fleet sent in full (asked for, or a player's): a copy is added, unless this game has that fleet already (the
     * world's own "added" got here first): a second copy was never updated, frozen where it appeared. Returns whether
     * it was added.
     */
    private boolean spawnCopy(JSONObject fleet) throws Exception {
        String id = fleet.getString("id");
        askedFor.remove(id);
        if (isOwnFleet(id) || Global.getSector().getEntityById(id) instanceof CampaignFleetAPI) return false;
        CampaignFleetAPI copy = Global.getFactory().createEmptyFleet(Faction.NO_FACTION, true);
        FleetSerializer.unSerializeFleet(fleet, copy);
        CopyAI.install(copy);
        return true;
    }

    /**
     * Two copies of the same fleet around us (from before spawnCopy checked, or a save that has them): only the one
     * the game finds by its id gets the updates; the others are removed.
     */
    private void removeDuplicateCopies() {
        CampaignFleetAPI own = Global.getSector().getPlayerFleet();
        if (own == null || own.getContainingLocation() == null) return;
        Set<String> seen = new HashSet<>();
        List<CampaignFleetAPI> extra = new ArrayList<>();
        for (CampaignFleetAPI fleet : own.getContainingLocation().getFleets()) {
            if (fleet.isPlayerFleet() || fleet.getId() == null || seen.add(fleet.getId())) continue;
            //a second one with this id: whichever of them isn't the one the game finds goes
            SectorEntityToken kept = Global.getSector().getEntityById(fleet.getId());
            for (CampaignFleetAPI same : own.getContainingLocation().getFleets()) {
                if (same != kept && !same.isPlayerFleet() && fleet.getId().equals(same.getId()) && !extra.contains(same)) extra.add(same);
            }
        }
        for (CampaignFleetAPI fleet : extra) fleet.getContainingLocation().removeEntity(fleet);
        if (!extra.isEmpty()) MultiplayerLog.log().warn("Removed " + extra.size() + " duplicate fleet copies");
    }

    /** Our own player fleet, which only we are in charge of: never replaced by a copy from someone else. */
    private boolean isOwnFleet(String fleetId) {
        return Global.getSector().getPlayerFleet() != null && fleetId.equals(Global.getSector().getPlayerFleet().getId());
    }

    private void executeTick() {
        try {
            //everyone knows our fleet by our client id (completeJoin gave it); a new player fleet (after losing the
            //old one) would otherwise be a stranger, and the server, looking for ours, would never find it
            CampaignFleetAPI own = Global.getSector().getPlayerFleet();
            if (own != null && client.clientId != null && !client.clientId.equals(own.getId())) {
                MultiplayerLog.log().info("Our fleet is new (" + own.getId() + "): it's " + client.clientId + " to the others again");
                own.setId(client.clientId);
            }
            fleetSync.sendOwnFleetUpdate(client);
            if (client.isSelfHosted) {
                //the host's own client shares the server's game, which is the world: its scripts must run
                sectorScriptsUtility.restoreScripts();
            } else {
                //the world runs on the server's game; here it's only shown
                sectorScriptsUtility.disableScripts();
            }
        } catch (Exception e) {
            MultiplayerLog.log().error("Unable to send own fleet update: " + e.getMessage(), e);
        }
    }
}