package matlabmaster.multiplayer.updates;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import matlabmaster.multiplayer.client.Client;
import matlabmaster.multiplayer.utils.FleetHelper;
import matlabmaster.multiplayer.utils.FleetSerializer;
import matlabmaster.multiplayer.utils.JsonDiffUtility;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class FleetSync
{
    private JSONObject lastTickFleet = new JSONObject();
    private JSONObject lastTickGlobalFleet = new JSONObject();
    /**
     * Each NPC fleet is serialized and diffed in full once every FULL_SYNC_TICKS ticks, a slice of the fleets
     * each tick; only its movement is sent every tick. Serializing and diffing every fleet every tick took
     * most of a frame on a full sector.
     */
    private static final int FULL_SYNC_TICKS = 20; //at 20 ticks per second: every fleet in full once a second
    private int globalTick = 0;

    public void sendOwnFleetUpdate(Client client) throws JSONException {
        JSONObject newFleet = FleetSerializer.serializeFleet(Global.getSector().getPlayerFleet());
        JSONObject diffs = JsonDiffUtility.getDifferences(lastTickFleet, newFleet);
        FleetSerializer.replaceWeaponGroupsDiffsWithFullState(diffs, newFleet);
        lastTickFleet = newFleet;
        if (diffs.length() > 0) {
            JSONObject packet = new JSONObject();
            packet.put("commandId","playerFleetUpdate");
            packet.put("fleetId", newFleet.getString("id")); // Root ID
            packet.put("from",client.clientId);
            packet.put("changes", diffs);
            client.send(String.valueOf(packet));
        }
    }

    public void handleRemoteFleetUpdate(JSONObject fleetDiffs) throws JSONException {
        FleetSerializer.applyFleetDiff((CampaignFleetAPI) Global.getSector().getEntityById(fleetDiffs.getString("fleetId")),fleetDiffs.getJSONObject("changes"));
    }

    /**
     * Same message and format as before ("globalFleetsUpdate": fleet id -> ADDED / REMOVED instruction, or a
     * nested diff), just built more cheaply: new fleets are sent in full as soon as they appear, gone fleets
     * as soon as they're gone, movement every tick, and everything else within FULL_SYNC_TICKS ticks.
     */
    public void sendGlobalFleetsUpdate(Client client) throws JSONException {
        sendGlobalFleetsUpdate(client::send);
    }

    /** Same, sending the message to any destination (the server broadcasts it to the clients). */
    public void sendGlobalFleetsUpdate(Consumer<String> send) throws JSONException {
        JSONObject diffs = computeGlobalDiffs();
        if (diffs.length() > 0) {
            JSONObject packet = new JSONObject();
            packet.put("commandId", "globalFleetsUpdate");
            packet.put("updates", diffs);
            send.accept(packet.toString());
        }
    }

    /** The NPC fleets present this tick, by id (filled by computeGlobalDiffs). */
    private final Map<String, CampaignFleetAPI> currentFleets = new HashMap<>();
    /** Per client: the NPC fleets it has been sent and not told were removed. */
    private final Map<String, Set<String>> sentToClient = new HashMap<>();

    /**
     * Server side: each client gets only the NPC fleets its fleet can see (VisibleFleets): a fleet coming into
     * view is sent in full (ADDED), one going out of view or gone is REMOVED, and the fleets it already has get
     * this tick's changes. Same message as globalFleetsUpdate, so clients need nothing new. A client whose fleet
     * isn't in the server's world yet is sent nothing.
     */
    public void sendVisibleFleetsUpdates(Map<String, Consumer<String>> clients) throws JSONException {
        JSONObject diffs = computeGlobalDiffs();
        sentToClient.keySet().retainAll(clients.keySet());
        for (Map.Entry<String, Consumer<String>> client : clients.entrySet()) {
            Set<String> sent = sentToClient.computeIfAbsent(client.getKey(), k -> new HashSet<>());
            Object own = Global.getSector().getEntityById(client.getKey());
            CampaignFleetAPI observer = own instanceof CampaignFleetAPI ? (CampaignFleetAPI) own : null;
            JSONObject updates = new JSONObject();
            for (Map.Entry<String, CampaignFleetAPI> entry : currentFleets.entrySet()) {
                String fleetId = entry.getKey();
                boolean was = sent.contains(fleetId);
                boolean sees = VisibleFleets.canSee(observer, entry.getValue(), was);
                if (sees && !was) {
                    updates.put(fleetId, JsonDiffUtility.instruction("ADDED", lastTickGlobalFleet.getJSONObject(fleetId), null));
                    sent.add(fleetId);
                } else if (!sees && was) {
                    updates.put(fleetId, JsonDiffUtility.instruction("REMOVED", null, fleetId));
                    sent.remove(fleetId);
                } else if (sees && diffs.has(fleetId)) {
                    updates.put(fleetId, diffs.get(fleetId));
                }
            }
            for (String fleetId : new ArrayList<>(sent)) {
                if (!currentFleets.containsKey(fleetId)) { //gone from the world
                    updates.put(fleetId, JsonDiffUtility.instruction("REMOVED", null, fleetId));
                    sent.remove(fleetId);
                }
            }
            if (updates.length() > 0) {
                JSONObject packet = new JSONObject();
                packet.put("commandId", "globalFleetsUpdate");
                packet.put("updates", updates);
                client.getValue().accept(packet.toString());
            }
        }
    }

    /** This tick's changes to every NPC fleet (see FULL_SYNC_TICKS), keeping lastTickGlobalFleet current. */
    private JSONObject computeGlobalDiffs() throws JSONException {
        currentFleets.clear();
        int slice = globalTick++ % FULL_SYNC_TICKS;
        JSONObject diffs = new JSONObject();
        Set<String> present = new HashSet<>();

        for (CampaignFleetAPI fleet : FleetHelper.getNPCFleets()) {
            String fleetId = fleet.getId();
            present.add(fleetId);
            currentFleets.put(fleetId, fleet);
            JSONObject last = lastTickGlobalFleet.optJSONObject(fleetId);
            if (last == null) { //new fleet: all of it, right away
                JSONObject full = FleetSerializer.serializeFleet(fleet);
                lastTickGlobalFleet.put(fleetId, full);
                diffs.put(fleetId, JsonDiffUtility.instruction("ADDED", full, null));
                continue;
            }

            JSONObject fleetDiff = new JSONObject();
            //movement, every tick
            JSONObject movement = FleetSerializer.serializeFleetMovement(fleet);
            for (String key : FleetSerializer.MOVEMENT_KEYS) {
                Object now = movement.get(key);
                Object before = last.opt(key);
                if (!now.equals(before)) {
                    fleetDiff.put(key, JsonDiffUtility.instruction("UPDATE", now, before));
                    last.put(key, now); //so the full diff below doesn't send it again
                }
            }
            //everything else, when it's this fleet's turn
            if (Math.floorMod(fleetId.hashCode(), FULL_SYNC_TICKS) == slice) {
                JSONObject full = FleetSerializer.serializeFleet(fleet);
                JSONObject rest = JsonDiffUtility.getDifferences(last, full);
                FleetSerializer.replaceWeaponGroupsDiffsWithFullState(rest, full);
                for (Iterator<?> it = rest.keys(); it.hasNext(); ) {
                    String key = (String) it.next();
                    fleetDiff.put(key, rest.get(key));
                }
                lastTickGlobalFleet.put(fleetId, full);
            }
            if (fleetDiff.length() > 0) {
                diffs.put(fleetId, fleetDiff);
            }
        }

        //fleets gone since the last tick
        List<String> gone = new ArrayList<>();
        for (Iterator<?> it = lastTickGlobalFleet.keys(); it.hasNext(); ) {
            String fleetId = (String) it.next();
            if (!present.contains(fleetId)) gone.add(fleetId);
        }
        for (String fleetId : gone) {
            diffs.put(fleetId, JsonDiffUtility.instruction("REMOVED", null, fleetId));
            lastTickGlobalFleet.remove(fleetId);
        }

        return diffs;
    }
}
