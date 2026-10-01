package matlabmaster.multiplayer.client;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.campaign.Faction;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.updates.BattleSync;
import matlabmaster.multiplayer.updates.DebrisSync;
import matlabmaster.multiplayer.updates.FleetSync;
import matlabmaster.multiplayer.updates.WorldSync;
import matlabmaster.multiplayer.utils.*;
import org.json.JSONObject;

import java.util.Arrays;
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
        if (client == null || !client.isConnected()) {
            //put back any sector scripts taken out while we were not the authority, so they are not lost
            sectorScriptsUtility.restoreScripts();
            if (factionSent != null) PlayerFactions.hideAll(); //just disconnected: nobody else is here any more
            factionSent = null;
            coloniesSent = null;
            debrisKnown.clear();
            HostileAwareTactics.unwrapAll();
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
            HostileAwareTactics.unwrapAll();
        }
        factionTimer += amount;
        if (factionSent == null || factionTimer >= PlayerFactions.RELATIONS_INTERVAL) {
            factionTimer = 0f;
            sendOwnFaction();
            if (!client.isSelfHosted) {
                markHostiles();
                syncDebris();
            }
        }
        coloniesTimer += amount;
        if (coloniesSent == null || coloniesTimer >= COLONIES_INTERVAL) {
            coloniesTimer = 0f;
            sendOwnColonies();
        }

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
        HostileAwareTactics.unwrapAll(); //never in a save: put back within a second
    }

    /** A new game was loaded: any scripts saved from the previous game belong to a sector that is gone. */
    public void onGameLoad() {
        sectorScriptsUtility.forgetScripts();
        interactionOrbit.forget();
        //our battles against the world's NPC fleets: the server's must lose the same ships (transient: not in the save)
        Global.getSector().addTransientListener(new BaseCampaignEventListener(false) {
            @Override
            public void reportBattleFinished(CampaignFleetAPI primaryWinner, BattleAPI battle) {
                if (!client.isConnected() || client.isSelfHosted || battle == null || !battle.isPlayerInvolved()) return;
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
                        if(!Global.getSector().isPaused()){
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
                    if (isOwnFleet(message.getJSONObject("fleet").getString("id"))) break;
                    FleetSerializer.unSerializeFleet(message.getJSONObject("fleet"),Global.getFactory().createEmptyFleet(Faction.NO_FACTION,true));
                    break;
                case "playerLeft":
                    FleetHelper.removeFleetById(message.getString("id"));
                    MultiplayerLog.log().info("[LEFT] " + message.optString("name", message.getString("id")) + " left the game");
                    break;
                case "playerJoined":
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
                case "debrisGone":
                    debrisKnown.remove(message.getString("id"));
                    if (!client.isSelfHosted) DebrisSync.remove(message.getString("id"));
                    break;
                case "playerRenamed":
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
                    if (isOwnFleet(message.getJSONObject("fleet").getString("id"))) break;
                    FleetSerializer.unSerializeFleet(message.getJSONObject("fleet"),Global.getFactory().createEmptyFleet(Faction.NO_FACTION,true));
                    MultiplayerLog.log().info("spawned "+ message.getJSONObject("fleet").getString("id")+ " fleet following request");
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
                        if (corrected != 0) {
                            MultiplayerLog.log().info("Clock set to the server's (it was " + (corrected / 3600000f) + " game hours off)");
                            //behind the world (it fast-forwarded, or we ran slow): our colonies' construction catches up too
                            if (corrected > 0) ColonyMirrors.catchUpConstruction(corrected / 86400000f);
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
                    existing.getContainingLocation().removeEntity(existing);
                }
            } else if ("ADDED".equals(action) && !(existing instanceof CampaignFleetAPI)) {
                //the full fleet is in the message: no need to ask for a snapshot
                FleetSerializer.unSerializeFleet(change.getJSONObject("value"), Global.getFactory().createEmptyFleet(Faction.NO_FACTION, true));
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
            if(!Global.getSector().isPaused()){
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
                if (debrisKnown.add(id)) fresh.put(id, fields.get(id));
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
     * Whether a fleet is hostile to us is its AI's call (CampaignFleet.isHostileTo asks it), and the copies of the
     * world's NPC fleets here don't think for themselves (the server moves them), so they kept showing as they were
     * when they arrived, neutral after we'd made their faction hostile. The NPC fleets around us whose faction is
     * hostile to us now (our reputation: this game's) are hostile to us: see HostileAwareTactics (wrapped within a
     * second, never in a save).
     */
    private void markHostiles() {
        //the AI's tactical module decides (a memory flag isn't asked): theirs is wrapped, see HostileAwareTactics
        HostileAwareTactics.wrapAround(Global.getSector().getPlayerFleet());
    }

    /**
     * Our colonies (they run in this game, the only authority on them), to the server, which mirrors them in the
     * world for the other players and keeps them there while we're offline. Sent when they change.
     */
    private void sendOwnColonies() {
        try {
            JSONObject packet = new JSONObject();
            packet.put("commandId", "colonies");
            packet.put("colonies", ColonyMirrors.describeOwnColonies());
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

    /** Our own player fleet, which only we are in charge of: never replaced by a copy from someone else. */
    private boolean isOwnFleet(String fleetId) {
        return Global.getSector().getPlayerFleet() != null && fleetId.equals(Global.getSector().getPlayerFleet().getId());
    }

    private void executeTick() {
        try {
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