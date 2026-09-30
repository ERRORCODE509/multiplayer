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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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
        int slice = globalTick++ % FULL_SYNC_TICKS;
        JSONObject diffs = new JSONObject();
        Set<String> present = new HashSet<>();

        for (CampaignFleetAPI fleet : FleetHelper.getNPCFleets()) {
            String fleetId = fleet.getId();
            present.add(fleetId);
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

        // Send Packet
        if (diffs.length() > 0) {
            JSONObject packet = new JSONObject();
            packet.put("commandId", "globalFleetsUpdate");
            packet.put("updates", diffs);
            send.accept(packet.toString());
        }
    }
}
