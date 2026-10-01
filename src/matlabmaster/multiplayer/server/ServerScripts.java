package matlabmaster.multiplayer.server;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.MultiplayerModPlugin;
import matlabmaster.multiplayer.updates.FleetSync;
import matlabmaster.multiplayer.utils.ClockUtility;
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

    /** Hides a dedicated server's own player fleet from the NPC fleets of its world (nobody plays it). */
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
        serverInstance.factionSync.advance(amount);
        serverInstance.markets.advance(amount);

        boolean paused = Global.getSector().isPaused();
        if (serverInstance.isDedicated()) {
            hideOwnFleet(true);
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
        MultiplayerLog.log().info("This game is now the world for every client (" + (serverInstance.isDedicated() ? "dedicated" : "host current game") + ")");
    }

    private void stopped() {
        wasRunning = false;
        System.getProperties().remove(PLAYER_POSITIONS_KEY); //back to vanilla spawning
        System.getProperties().remove(FULL_RATE_LOCATIONS_KEY); //and to vanilla location updates
        serverInstance.interactions.clear(); //nobody is connected to talk to anyone
        serverInstance.factionSync.stopped(); //nor in any player faction
        heldAt.clear();
        orbiting.clear(); //the players' fleets themselves are removed just below
        hideOwnFleet(false);
        //a dedicated server kept copies of the players' fleets: they don't belong in its game
        List<CampaignFleetAPI> copies = new ArrayList<>();
        for (LocationAPI location : Global.getSector().getAllLocations()) {
            for (CampaignFleetAPI fleet : location.getFleets()) {
                if (fleet.hasTag("playerFleet") && !fleet.isPlayerFleet()) copies.add(fleet);
            }
        }
        for (CampaignFleetAPI fleet : copies) {
            fleet.getContainingLocation().removeEntity(fleet);
        }
        if (!copies.isEmpty()) MultiplayerLog.log().info("Removed " + copies.size() + " player fleet copies from the server's game");
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
        for (Map.Entry<String, Server.Interaction> entry : serverInstance.interactions.entrySet()) {
            String clientId = entry.getKey();
            Server.Interaction interaction = entry.getValue();
            SectorEntityToken target = Global.getSector().getEntityById(interaction.target);
            if (target == null) continue; //gone (destroyed, despawned)
            if (target instanceof CampaignFleetAPI) {
                CampaignFleetAPI fleet = (CampaignFleetAPI) target;
                if (fleet.isPlayerFleet() || fleet.hasTag("playerFleet")) continue; //players move themselves
                held.add(interaction.target);
                //where the player's game saw it (by their fleet, here too), or where it is
                Vector2f at = heldAt.computeIfAbsent(interaction.target,
                        id -> new Vector2f(interaction.seenAt != null ? interaction.seenAt : fleet.getLocation()));
                fleet.setLocation(at.x, at.y);
                fleet.getVelocity().set(0f, 0f);
            } else if (target.getOrbit() != null) {
                orbitWhileTalking(clientId, target);
            }
        }
        heldAt.keySet().retainAll(held);

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
    private void orbitWhileTalking(String clientId, SectorEntityToken target) {
        SectorEntityToken copy = Global.getSector().getEntityById(clientId);
        if (!(copy instanceof CampaignFleetAPI) || copy.getContainingLocation() != target.getContainingLocation()) return;
        if (copy.getOrbitFocus() == target) return; //already
        float angle = Misc.getAngleInDegrees(target.getLocation(), copy.getLocation());
        float radius = Misc.getDistance(target.getLocation(), copy.getLocation());
        copy.setCircularOrbit(target, angle, radius, 100000f); //practically standing still, as in their game
        orbiting.put(clientId, target.getId());
    }

    private void hideOwnFleet(boolean hide) {
        if (hide == ownFleetHidden) return;
        CampaignFleetAPI own = Global.getSector().getPlayerFleet();
        if (own == null) return;
        ownFleetHidden = hide;
        if (hide) {
            own.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true);
            own.getStats().getDetectedRangeMod().modifyMult(HIDDEN_ID, 0f, "Dedicated multiplayer server");
        } else {
            own.getMemoryWithoutUpdate().unset(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS);
            own.getStats().getDetectedRangeMod().unmodify(HIDDEN_ID);
        }
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
