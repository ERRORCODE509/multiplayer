package matlabmaster.multiplayer.server;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.ai.ModularFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.TacticalModulePlugin;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.MultiplayerModPlugin;
import matlabmaster.multiplayer.updates.FleetSync;
import matlabmaster.multiplayer.utils.ClockUtility;
import matlabmaster.multiplayer.utils.FleetHelper;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs on the game thread of the game hosting the server, which is the only authority: this game's NPC fleets,
 * clock and scripts are the world. Sends the world to the clients 20 times a second, answers what the network
 * threads handed over, and tells the clients when the world stops.
 */
public class ServerScripts implements EveryFrameScript {
    private static final float INTERVAL = 0.05f; //20 ticks per second, like the clients
    /**
     * Where the players are, for the multiplayer agent (-javaagent, added by the launcher): it makes vanilla's fleet
     * managers spawn and keep AI fleets around the nearest of these instead of around this game's own player fleet
     * only. A float[] {x0, y0, x1, y1, ...} of hyperspace positions in System.getProperties(), which the agent reads,
     * so neither side links to the other. Without the agent it's simply unused.
     */
    public static final String PLAYER_POSITIONS_KEY = "multiplayer.nearestPlayer.positions";
    private static final String AGENT_ACTIVE_KEY = "multiplayer.nearestPlayer.active";
    /**
     * The locations the players are in, for the agent: vanilla runs every location but this game's own player
     * fleet's in one-second steps, and the agent runs these every frame instead. A Set of LocationAPI, same idea.
     */
    public static final String FULL_RATE_LOCATIONS_KEY = "multiplayer.fullRate.locations";

    /** Hides a dedicated server's own player fleet from the NPC fleets of its world, and it uses no fuel or supplies (nobody plays it). */
    private static final String HIDDEN_ID = "multiplayer_dedicated_server";

    private final Server serverInstance;
    private FleetSync fleetSync = new FleetSync();
    private float timer = 0f;
    /** How often a dedicated server checks it still has every connected player's fleet (seconds). */
    private static final float MISSING_FLEETS_INTERVAL = 2f;
    private float missingFleetsTimer = 0f;
    private boolean wasRunning = false;
    private boolean worldWasPaused = false;
    private boolean ownFleetHidden = false;
    /** Where each fleet a player is talking to is held (fleet id -> location), see holdInteractionTargets. */
    private final Map<String, Vector2f> heldAt = new HashMap<>();
    /** Players' fleets kept by what they're talking to (client id -> target id), see orbitWhileTalking. */
    private final Map<String, String> orbiting = new HashMap<>();
    /** Where players' fleets are stopped while they're in a dialog (client id -> location). */
    private final Map<String, Vector2f> pinnedAt = new HashMap<>();
    /** How close (beyond touching) an NPC fleet chasing a player gets before it intercepts them. */
    private static final float INTERCEPT_MARGIN = 25f;
    /** Seconds before the same fleet can intercept the same player again (after they've had it out, or got away). */
    private static final float INTERCEPT_COOLDOWN = 10f;
    /** Seconds a player's game has to open an interception before both fleets are let go. */
    private static final float INTERCEPT_ANSWER_TIME = 3f;
    private float clock = 0f;
    private final Map<String, Float> lastIntercept = new HashMap<>();
    /** Interceptions waiting for the player's game to open them (client id -> the hold, and since when). */
    private final Map<String, Server.Interaction> intercepts = new HashMap<>();
    private final Map<String, Float> interceptedAt = new HashMap<>();

    public ServerScripts(Server serverInstance){
        this.serverInstance = serverInstance;
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
        //work the network threads handed over because it touches the game
        Runnable task;
        while ((task = serverInstance.gameThreadTasks.poll()) != null) {
            if (serverInstance.isRunning) task.run();
        }
        if (!serverInstance.isRunning) {
            if (wasRunning) stopped();
            return;
        }
        if (!wasRunning) started();

        holdInteractionTargets();
        checkInterceptions(amount);
        serverInstance.factionSync.advance(amount);
        serverInstance.markets.advance(amount);
        serverInstance.debris.advance(amount);
        serverInstance.raids.advance(amount);
        serverInstance.entities.advance(amount);
        serverInstance.ownership.advance(amount);

        boolean paused = Global.getSector().isPaused();
        if (serverInstance.isDedicated()) {
            hideOwnFleet(true);
            keepOwnFleetSupplied(amount);
            //its own player at a player's colony trades there as a visitor (ServerMarkets)
            InteractionDialogAPI dialog = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
            serverInstance.markets.ownDialog(dialog == null ? null : dialog.getInteractionTarget());
            //nobody plays here, so nothing should hold the world up
            if (paused && !Global.getSector().getCampaignUI().isShowingDialog()) {
                Global.getSector().setPaused(false);
                paused = false;
            }
        }
        if (paused != worldWasPaused) {
            worldWasPaused = paused;
            broadcastWorldPaused(paused);
        }
        if (paused) return;

        if (serverInstance.isDedicated()) {
            missingFleetsTimer += amount;
            if (missingFleetsTimer >= MISSING_FLEETS_INTERVAL) {
                missingFleetsTimer = 0f;
                serverInstance.requestMissingPlayerFleets();
            }
        }

        timer += amount;
        if (timer < INTERVAL) return;
        timer = Math.min(timer - INTERVAL, INTERVAL); //never try to catch up on a backlog of ticks
        try {
            publishPlayerPositions(); //for the multiplayer agent, 20 times a second
        } catch (Exception e) {
            //never let the agent's helper hold up the world updates
            MultiplayerLog.log().warn("Couldn't publish the player positions for the agent: " + e.getMessage());
        }
        try {
            fleetSync.sendVisibleFleetsUpdates(serverInstance.worldClients()); //each client only what its fleet can see
            serverInstance.broadcastWorld(ClockUtility.serverTimePacket().toString());
            if (MultiplayerModPlugin.getUI() != null) {
                MultiplayerModPlugin.getUI().setServerTime(Global.getSector().getClock().getTimestamp());
            }
        } catch (Exception e) {
            MultiplayerLog.log().error("Failed to send the world update: " + e.getMessage(), e);
        }
    }

    /**
     * Undo anything hosting changed in this game before it's saved, so the save is a normal single-player save
     * (the next frame redoes it while still hosting).
     */
    public void beforeGameSave() {
        hideOwnFleet(false);
    }

    private void started() {
        wasRunning = true;
        MultiplayerLog.log().info(Boolean.TRUE.equals(System.getProperties().get(AGENT_ACTIVE_KEY))
                ? "Multiplayer agent active: AI fleets spawn around every player"
                : "Multiplayer agent not active: vanilla only spawns most AI fleets near this game's own player fleet (start the server with the launcher to fix that)");
        fleetSync = new FleetSync(); //the new clients know nothing yet: start the diffs from scratch
        worldWasPaused = false;
        timer = 0f;
        serverInstance.raids.started();
        MultiplayerLog.log().info("This game is now the world for every client (" + (serverInstance.isDedicated() ? "dedicated" : "host current game") + ")");
    }

    private void stopped() {
        wasRunning = false;
        System.getProperties().remove(PLAYER_POSITIONS_KEY); //back to vanilla spawning
        System.getProperties().remove(FULL_RATE_LOCATIONS_KEY); //and to vanilla location updates
        serverInstance.interactions.clear(); //nobody is connected to talk to anyone
        serverInstance.factionSync.stopped(); //nor in any player faction
        serverInstance.debris.stopped();
        serverInstance.raids.stopped();
        serverInstance.entities.stopped();
        serverInstance.ownership.stopped();
        heldAt.clear();
        orbiting.clear(); //the players' fleets themselves are removed just below
        pinnedAt.clear();
        intercepts.clear();
        interceptedAt.clear();
        hideOwnFleet(false);
        //this game kept copies of the players' fleets: they don't belong in it
        int copies = FleetHelper.removePlayerCopies();
        if (copies > 0) MultiplayerLog.log().info("Removed " + copies + " player fleet copies from the server's game");
    }

    /**
     * The players: every connected player's fleet as it exists in this game (the copies, tagged playerFleet), plus
     * this game's own player fleet when someone plays it ("host current game"; a dedicated server's is a dummy).
     */
    private void publishPlayerPositions() {
        List<Float> xy = new ArrayList<>();
        Set<LocationAPI> withPlayers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (LocationAPI location : Global.getSector().getAllLocations()) {
            for (CampaignFleetAPI fleet : location.getFleets()) {
                boolean player = fleet.isPlayerFleet() ? !serverInstance.isDedicated() : fleet.hasTag("playerFleet");
                if (!player) continue;
                withPlayers.add(location);
                if (fleet.getLocationInHyperspace() == null) continue;
                xy.add(fleet.getLocationInHyperspace().x);
                xy.add(fleet.getLocationInHyperspace().y);
            }
        }
        float[] positions = new float[xy.size()];
        for (int i = 0; i < positions.length; i++) positions[i] = xy.get(i);
        System.getProperties().put(PLAYER_POSITIONS_KEY, positions);
        System.getProperties().put(FULL_RATE_LOCATIONS_KEY, withPlayers);
    }

    /**
     * In single player a dialog pauses the game; here the world runs on. An NPC fleet a player is in a dialog with
     * is held where their game saw it (by their fleet), or it would fly off mid-conversation (or be elsewhere than
     * they fight it); runs after this frame's movement (sector scripts come after the locations), so it doesn't move
     * at all. A planet or station they talk to goes on, and their fleet stays by it, as in their game.
     */
    private void holdInteractionTargets() {
        Set<String> held = new HashSet<>();
        Set<String> pinned = new HashSet<>();
        for (Map.Entry<String, Server.Interaction> entry : serverInstance.interactions.entrySet()) {
            String clientId = entry.getKey();
            Server.Interaction interaction = entry.getValue();
            SectorEntityToken target = Global.getSector().getEntityById(interaction.target);
            SectorEntityToken own = Global.getSector().getEntityById(clientId);
            CampaignFleetAPI player = own instanceof CampaignFleetAPI ? (CampaignFleetAPI) own : null;
            if (target == null) continue; //gone (destroyed, despawned)
            if (target.getOrbit() != null && !(target instanceof CampaignFleetAPI)) {
                if (player != null) orbitWhileTalking(clientId, player, target, interaction);
                continue;
            }
            if (target instanceof CampaignFleetAPI) {
                CampaignFleetAPI fleet = (CampaignFleetAPI) target;
                if (!fleet.isPlayerFleet() && !fleet.hasTag("playerFleet")) { //players move themselves
                    held.add(interaction.target);
                    //where the player's game saw it (by their fleet, here too), or where it is
                    Vector2f at = heldAt.computeIfAbsent(interaction.target,
                            id -> new Vector2f(interaction.seenAt != null ? interaction.seenAt : fleet.getLocation()));
                    fleet.setLocation(at.x, at.y);
                    fleet.getVelocity().set(0f, 0f);
                }
            }
            //the player's fleet stops where their game has it: it sends no movement during the dialog, and this
            //copy would otherwise fly on to wherever it was last headed (an NPC fleet, a derelict...)
            if (player != null) {
                pinned.add(clientId);
                Vector2f at = pinnedAt.computeIfAbsent(clientId,
                        id -> new Vector2f(interaction.playerAt != null ? interaction.playerAt : player.getLocation()));
                stop(player, at);
            }
        }
        heldAt.keySet().retainAll(held);
        pinnedAt.keySet().retainAll(pinned);

        //players who aren't talking to what their fleet orbits any more: their game moves it again
        for (Iterator<Map.Entry<String, String>> it = orbiting.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, String> entry = it.next();
            Server.Interaction interaction = serverInstance.interactions.get(entry.getKey());
            if (interaction != null && interaction.target.equals(entry.getValue())) continue;
            SectorEntityToken copy = Global.getSector().getEntityById(entry.getKey());
            if (copy != null && copy.getOrbitFocus() != null && entry.getValue().equals(copy.getOrbitFocus().getId())) {
                copy.setOrbit(null);
            }
            it.remove();
        }
    }

    /**
     * A player talking to a planet, station or anything else that orbits: their game paused for the dialog and
     * keeps their fleet by it (InteractionOrbit), sending no movement meanwhile, so this game's copy of their fleet
     * does the same, instead of standing still while the planet goes on and snapping to it afterwards.
     */
    private void orbitWhileTalking(String clientId, CampaignFleetAPI player, SectorEntityToken target, Server.Interaction interaction) {
        if (player.getContainingLocation() != target.getContainingLocation()) return;
        if (player.getOrbitFocus() != target) {
            //by it as their game has them (the angle and distance it saw), wherever it is here
            boolean seen = interaction.seenAt != null && interaction.playerAt != null;
            Vector2f from = seen ? interaction.seenAt : target.getLocation();
            Vector2f to = seen ? interaction.playerAt : player.getLocation();
            float angle = Misc.getAngleInDegrees(from, to);
            float radius = Misc.getDistance(from, to);
            player.setCircularOrbit(target, angle, radius, 100000f); //practically standing still, as in their game
            orbiting.put(clientId, target.getId());
        }
        //and headed nowhere: once the orbit lets go it stays put, rather than flying on to where it was headed
        stop(player, player.getLocation());
    }

    /**
     * NPC fleets that catch a player. Their AI chases players' fleets here (the world), but only the player's game
     * can start the encounter, and its copy of the NPC fleet isn't chasing anyone. So when one this game's AI is
     * pursuing reaches a player, their game is told to open the encounter, as vanilla does when it intercepts the
     * player; both are held where they are at once (before they touch: this game must never fight it out between
     * them itself) until the dialog opens there, which then holds them as any dialog does.
     */
    private void checkInterceptions(float amount) {
        clock += amount;
        //an interception their game didn't open (it had a dialog up, or never answered): let them go
        for (Iterator<Map.Entry<String, Float>> it = interceptedAt.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Float> entry = it.next();
            if (clock - entry.getValue() < INTERCEPT_ANSWER_TIME) continue;
            Server.Interaction interaction = serverInstance.interactions.get(entry.getKey());
            if (interaction != null && interaction == intercepts.get(entry.getKey())) serverInstance.interactions.remove(entry.getKey());
            intercepts.remove(entry.getKey());
            it.remove();
        }
        for (String clientId : serverInstance.worldClients().keySet()) {
            if (serverInstance.interactions.containsKey(clientId)) continue; //in a dialog already
            SectorEntityToken own = Global.getSector().getEntityById(clientId);
            if (!(own instanceof CampaignFleetAPI) || own.getContainingLocation() == null) continue;
            CampaignFleetAPI player = (CampaignFleetAPI) own;
            for (CampaignFleetAPI fleet : player.getContainingLocation().getFleets()) {
                if (fleet == player || fleet.isPlayerFleet() || fleet.hasTag("playerFleet")) continue;
                if (!(fleet.getAI() instanceof ModularFleetAIAPI)) continue;
                TacticalModulePlugin tactical = ((ModularFleetAIAPI) fleet.getAI()).getTacticalModule();
                if (tactical == null || tactical.getTarget() != player) continue; //not chasing them
                float reach = fleet.getRadius() + player.getRadius() + INTERCEPT_MARGIN;
                if (Misc.getDistance(fleet.getLocation(), player.getLocation()) > reach) continue;
                String pair = fleet.getId() + "|" + clientId;
                Float last = lastIntercept.get(pair);
                if (last != null && clock - last < INTERCEPT_COOLDOWN) continue; //they just had it out
                lastIntercept.put(pair, clock);
                Server.Interaction interaction = new Server.Interaction(fleet.getId(), new Vector2f(fleet.getLocation()), new Vector2f(player.getLocation()));
                serverInstance.interactions.put(clientId, interaction);
                intercepts.put(clientId, interaction);
                interceptedAt.put(clientId, clock);
                try {
                    JSONObject packet = new JSONObject();
                    packet.put("commandId", "intercepted");
                    packet.put("fleetId", fleet.getId());
                    serverInstance.sendTo(clientId, packet.toString());
                    MultiplayerLog.log().info(fleet.getName() + " intercepted " + serverInstance.who(clientId));
                } catch (Exception e) {
                    MultiplayerLog.log().error("Couldn't tell " + clientId + " they're intercepted", e);
                }
                break;
            }
        }
    }

    /**
     * A player's fleet stops here: no speed, and nowhere else to go. And no NPC fleet goes after it meanwhile (in
     * single player the world is paused: here they'd gather around and all be at them once the dialog closes),
     * for a moment at a time, renewed every frame of the dialog and gone by itself after it.
     */
    private static void stop(CampaignFleetAPI fleet, Vector2f at) {
        fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true, 0.1f);
        if (fleet.getOrbit() == null) fleet.setLocation(at.x, at.y);
        fleet.getVelocity().set(0f, 0f);
        fleet.setMoveDestination(at.x, at.y);
    }

    private void hideOwnFleet(boolean hide) {
        if (hide == ownFleetHidden) return;
        CampaignFleetAPI own = Global.getSector().getPlayerFleet();
        if (own == null) return;
        ownFleetHidden = hide;
        suppliesKept = hide ? own.getCargo().getSupplies() : -1f;
        fuelKept = hide ? own.getCargo().getFuel() : -1f;
        if (hide) {
            own.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true);
            own.getStats().getDetectedRangeMod().modifyMult(HIDDEN_ID, 0f, "Dedicated multiplayer server");
            //nobody plays it, so it shouldn't run out of anything while it sits there (or is moved around)
            own.getStats().getFuelUseHyperMult().modifyMult(HIDDEN_ID, 0f, "Dedicated multiplayer server");
            own.getStats().getFuelUseNormalMult().modifyMult(HIDDEN_ID, 0f, "Dedicated multiplayer server");
            for (FleetMemberAPI member : own.getFleetData().getMembersListCopy()) {
                member.getStats().getSuppliesPerMonth().modifyMult(HIDDEN_ID, 0f, "Dedicated multiplayer server");
            }
        } else {
            own.getMemoryWithoutUpdate().unset(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS);
            own.getStats().getDetectedRangeMod().unmodify(HIDDEN_ID);
            own.getStats().getFuelUseHyperMult().unmodify(HIDDEN_ID);
            own.getStats().getFuelUseNormalMult().unmodify(HIDDEN_ID);
            for (FleetMemberAPI member : own.getFleetData().getMembersListCopy()) {
                member.getStats().getSuppliesPerMonth().unmodify(HIDDEN_ID);
            }
        }
    }

    /** What the dedicated server's own fleet has to keep: never less (-1: not hidden). Goes up as it gains some. */
    private float suppliesKept = -1f, fuelKept = -1f;
    private float suppliedTimer = 0f;

    /**
     * Every frame while hidden: the supplies and fuel it spent put back (repairs and CR recovery after a battle
     * aren't monthly upkeep), as Console Commands' infinitesupplies does; more than it had (bought, given), or what
     * it has while paused (sold at a market), is the new level. Every second, the no-supplies modifier on every ship again: vanilla rebuilds a ship's stats after
     * battles, repairs and refits, dropping it, and new ships never had it.
     */
    private void keepOwnFleetSupplied(float amount) {
        if (!ownFleetHidden) return;
        CampaignFleetAPI own = Global.getSector().getPlayerFleet();
        if (own == null) return;
        float supplies = own.getCargo().getSupplies(), fuel = own.getCargo().getFuel();
        //paused (a market, a dialog): whatever it has now is what it keeps, so selling some sticks
        boolean paused = Global.getSector().isPaused();
        if (paused || supplies > suppliesKept) suppliesKept = supplies;
        else if (supplies < suppliesKept) own.getCargo().addSupplies(suppliesKept - supplies);
        if (paused || fuel > fuelKept) fuelKept = fuel;
        else if (fuel < fuelKept) own.getCargo().addFuel(fuelKept - fuel);
        suppliedTimer += amount;
        if (suppliedTimer < 1f) return;
        suppliedTimer = 0f;
        for (FleetMemberAPI member : own.getFleetData().getMembersListCopy()) {
            member.getStats().getSuppliesPerMonth().modifyMult(HIDDEN_ID, 0f, "Dedicated multiplayer server");
        }
        own.getStats().getFuelUseHyperMult().modifyMult(HIDDEN_ID, 0f, "Dedicated multiplayer server");
        own.getStats().getFuelUseNormalMult().modifyMult(HIDDEN_ID, 0f, "Dedicated multiplayer server");
    }

    private void broadcastWorldPaused(boolean paused) {
        try {
            JSONObject packet = new JSONObject();
            packet.put("commandId", paused ? "worldPaused" : "worldResumed");
            serverInstance.broadcastWorld(packet.toString());
            MultiplayerLog.log().info(paused ? "The world is paused for every client (the host is in a dialog or menu)" : "The world runs again");
        } catch (Exception e) {
            MultiplayerLog.log().error("Failed to tell the clients the world " + (paused ? "paused" : "resumed"), e);
        }
    }
}
