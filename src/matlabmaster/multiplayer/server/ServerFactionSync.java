package matlabmaster.multiplayer.server;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.utils.PlayerFactions;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server's side of the players' factions (see PlayerFactions), on the game thread: gives each player's faction
 * their reputation and look as their game sends them, and sends the clients the world's faction relations (the
 * changes once a second; all of them, and every player's look, to a player who just joined).
 */
public class ServerFactionSync {
    private final Server server;
    /** Players who haven't been sent the relations and looks yet (added from the network thread). */
    private final Set<String> newClients = ConcurrentHashMap.newKeySet();
    /** How each player faction in use looks (null: unaligned), for players who join later. */
    private final Map<String, JSONObject> looks = new HashMap<>();
    private JSONObject lastRelations = new JSONObject();
    private float timer = 0f;

    ServerFactionSync(Server server) {
        this.server = server;
    }

    /** Network thread: a player joined with this faction. */
    void joined(String clientId) {
        newClients.add(clientId);
    }

    /** A player's {"look": describeOwnFaction() or absent, "reputation": ownReputation()}. */
    void playerFaction(String clientId, JSONObject message) {
        String faction = server.clientFactions.get(clientId);
        if (!PlayerFactions.isSlot(faction)) return; //no faction of their own (all taken): nothing of theirs to apply
        try {
            JSONObject look = message.optJSONObject("look");
            PlayerFactions.applyLook(faction, look);
            looks.put(faction, look);
            if (message.has("reputation")) PlayerFactions.applyReputation(faction, message.getJSONObject("reputation"));
            server.broadcastExcept(clientId, lookPacket(faction, look).toString());
        } catch (JSONException e) {
            MultiplayerLog.log().error("Couldn't apply " + clientId + "'s faction", e);
        }
    }

    /** A player left: their faction goes back to unaligned and neutral to everyone, for the next player. */
    void freed(String faction) {
        if (!PlayerFactions.isSlot(faction)) return;
        looks.remove(faction);
        PlayerFactions.applyLook(faction, null);
        FactionAPI slot = Global.getSector().getFaction(faction);
        if (slot != null) {
            for (FactionAPI other : Global.getSector().getAllFactions()) {
                if (other != slot) slot.setRelationship(other.getId(), 0f);
            }
        }
        try {
            server.broadcast(lookPacket(faction, null).toString());
        } catch (JSONException e) {
            MultiplayerLog.log().error("Couldn't tell the clients " + faction + " is free", e);
        }
    }

    /** Every frame, from ServerScripts. */
    void advance(float amount) {
        try {
            if (!newClients.isEmpty()) {
                JSONObject all = PlayerFactions.worldRelations();
                for (String clientId : newClients.toArray(new String[0])) {
                    newClients.remove(clientId);
                    server.sendTo(clientId, relationsPacket(all).toString());
                    for (Map.Entry<String, JSONObject> look : looks.entrySet()) {
                        server.sendTo(clientId, lookPacket(look.getKey(), look.getValue()).toString());
                    }
                }
            }
            timer += amount;
            if (timer < PlayerFactions.RELATIONS_INTERVAL) return;
            timer = 0f;
            JSONObject now = PlayerFactions.worldRelations();
            JSONObject changes = PlayerFactions.changed(lastRelations, now);
            lastRelations = now;
            if (changes.length() > 0) server.broadcast(relationsPacket(changes).toString());
        } catch (JSONException e) {
            MultiplayerLog.log().error("Couldn't send the faction relations", e);
        }
    }

    private static JSONObject relationsPacket(JSONObject relations) throws JSONException {
        JSONObject packet = new JSONObject();
        packet.put("commandId", "factionRelations");
        packet.put("relations", relations);
        return packet;
    }

    private static JSONObject lookPacket(String faction, JSONObject look) throws JSONException {
        JSONObject packet = new JSONObject();
        packet.put("commandId", "playerFactionLook");
        packet.put("faction", faction);
        if (look != null) packet.put("look", look);
        return packet;
    }
}
