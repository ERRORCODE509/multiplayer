package matlabmaster.multiplayer.client;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.intel.group.FGAction;
import com.fs.starfarer.api.impl.campaign.intel.group.FGWaitAction;
import com.fs.starfarer.api.impl.campaign.intel.group.FleetGroupIntel;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.updates.RaidSync;
import matlabmaster.multiplayer.utils.PlayerIdentity;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The owner's side of the raids on this game's colonies (see RaidSync): a raid its crisis makes while connected is
 * handed over to the world, which runs it where every player can see and fight it. This game keeps its own
 * (the crisis knows it by it, the intel tab shows it) frozen: out of the sector's scripts and without its route, so
 * it never spawns fleets here, even after leaving the server. The world says what it's doing (the intel's updates
 * come as the actions finish) and how it ended: defeated (by anyone), it's aborted here, which is what the crisis
 * counts as beating it; over, it ends. What it did to the colony comes separately (RaidSync.applyHit).
 *
 * The raids handed over are kept in this game's save (raid id -> raid), so it still knows them on the next session,
 * and a server that never heard of one (another world, or one that wasn't saved) is handed it again.
 */
public class OwnRaids {
    private static final String KEY = "multiplayer_handedRaids";
    private static final float INTERVAL = 1f;
    private float timer = 0f;
    /** Raids handed over to the server this session (a new server, or the same after reconnecting, hears of all again). */
    private final Set<String> sentThisSession = new HashSet<>();

    @SuppressWarnings("unchecked")
    private static Map<String, GenericRaidFGI> handed() {
        Map<String, Object> persistent = Global.getSector().getPersistentData();
        Object stored = persistent.get(KEY);
        if (!(stored instanceof HashMap)) {
            stored = new HashMap<String, GenericRaidFGI>();
            persistent.put(KEY, stored);
        }
        return (Map<String, GenericRaidFGI>) stored;
    }

    /** A sector script that must stay out of the sector's scripts (SectorScriptsUtility): a raid the world runs. */
    public static boolean isFrozen(EveryFrameScript script) {
        if (!(script instanceof GenericRaidFGI)) return false;
        GenericRaidFGI raid = (GenericRaidFGI) script;
        return !raid.isEnding() && !raid.isEnded() && handed().containsValue(raid);
    }

    /** Every frame while connected to someone else's server (not when this game is the world). */
    public void advance(float amount, Client client) {
        timer += amount;
        if (timer < INTERVAL) return;
        timer = 0f;
        Map<String, GenericRaidFGI> handed = handed();
        for (IntelInfoPlugin intel : Global.getSector().getIntelManager().getIntel()) {
            if (!(intel instanceof FleetGroupIntel) || handed.containsValue(intel)) continue;
            FleetGroupIntel group = (FleetGroupIntel) intel;
            if (group.isEnding() || group.isEnded() || !RaidSync.canHandOver(group)) continue;
            GenericRaidFGI raid = (GenericRaidFGI) group;
            String id = PlayerIdentity.id() + "-" + UUID.randomUUID().toString().substring(0, 8); //unique to this player
            handed.put(id, raid);
            freeze(raid);
            MultiplayerLog.log().info("A " + raid.getBaseName() + " is coming for " + raid.getParams().raidParams.where.getName() + ": the world runs it (" + id + ")");
        }
        for (Map.Entry<String, GenericRaidFGI> entry : new ArrayList<>(handed.entrySet())) {
            GenericRaidFGI raid = entry.getValue();
            if (raid.isEnding() || raid.isEnded()) {
                //ended here (the crisis called it off): the world's goes home too
                handed.remove(entry.getKey());
                try {
                    client.send(new JSONObject().put("commandId", "raidCallOff").put("id", entry.getKey()).toString());
                } catch (JSONException e) {
                    MultiplayerLog.log().error("Couldn't call the raid " + entry.getKey() + " off", e);
                }
                continue;
            }
            if (sentThisSession.contains(entry.getKey())) continue;
            try {
                JSONObject packet = new JSONObject();
                packet.put("commandId", "raidHandOver");
                packet.put("raid", RaidSync.describe(entry.getKey(), raid));
                client.send(packet.toString());
                sentThisSession.add(entry.getKey());
            } catch (Exception e) {
                MultiplayerLog.log().error("Couldn't hand the raid " + entry.getKey() + " over", e);
                sentThisSession.add(entry.getKey()); //not every second
            }
        }
    }

    /** Out of the scripts (taken out every tick anyway while connected) and its route gone: it never moves here. */
    private static void freeze(GenericRaidFGI raid) {
        Global.getSector().removeScript(raid);
        if (raid.getRoute() != null) RouteManager.getInstance().removeRoute(raid.getRoute());
    }

    /** Disconnected, or another game: the next server hears of every raid again. */
    public void reset() {
        sentThisSession.clear();
        timer = 0f;
    }

    /** The world's raid moved on to another action: the ones before it are done here too (with their intel updates). */
    public static void action(String id, String actionId) {
        GenericRaidFGI raid = handed().get(id);
        if (raid == null || raid.isEnding() || raid.isEnded() || raid.getAction(actionId) == null) return;
        while (!raid.getActions().isEmpty() && raid.getCurrentAction() != raid.getAction(actionId)) {
            FGAction done = raid.getActions().remove(0);
            done.setActionFinished(true);
            if (GenericRaidFGI.PREPARE_ACTION.equals(done.getId())) {
                raid.sendUpdateIfPlayerHasIntel(FleetGroupIntel.FLEET_LAUNCH_UPDATE, false);
            } else if (done.getId() != null && !(done instanceof FGWaitAction)) {
                raid.sendUpdateIfPlayerHasIntel(done.getId(), false);
            }
        }
    }

    /**
     * The world's raid ended: defeated or called off (aborted, which the crisis counts as beaten: its listener
     * hears it), over, or never started (cancelled: it couldn't be made there). It's back in the scripts to end.
     */
    public static void ended(String id, String outcome) {
        GenericRaidFGI raid = handed().remove(id);
        if (raid == null || raid.isEnding() || raid.isEnded()) return;
        if (RaidSync.ABORTED.equals(outcome)) {
            raid.abort();
            MultiplayerLog.log().info("The " + raid.getBaseName() + " on " + raid.getParams().raidParams.where.getName() + " was defeated");
        } else {
            raid.finish(false);
            MultiplayerLog.log().info("The " + raid.getBaseName() + " on " + raid.getParams().raidParams.where.getName() + " is over (" + outcome + ")");
        }
        if (!Global.getSector().getScripts().contains(raid)) Global.getSector().addScript(raid); //to finish ending
    }

    /** A colonyHit, raidAction or raidEnded message. */
    public static void received(JSONObject message) throws JSONException {
        switch (message.getString("commandId")) {
            case "colonyHit":
                RaidSync.applyHit(message.getJSONObject("hit"));
                break;
            case "raidAction":
                action(message.getString("id"), message.getString("action"));
                break;
            case "raidEnded":
                ended(message.getString("id"), message.getString("outcome"));
                break;
        }
    }
}
