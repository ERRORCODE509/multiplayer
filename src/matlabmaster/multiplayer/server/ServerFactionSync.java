package matlabmaster.multiplayer.server;

import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.utils.ColonyMirrors;
import matlabmaster.multiplayer.utils.PlayerFactions;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server's side of the players' factions and colonies (see PlayerFactions, ColonyMirrors), on the game thread:
 * gives each player's faction their reputation and look, and mirrors their colonies, as their game sends them
 * (kept in the world's PlayerRegistry while they're offline); sends the clients the world's faction relations (the
 * changes once a second), and, to a player who just joined, all of them with every player's faction and colonies.
 */
public class ServerFactionSync {
    private final Server server;
    /** Players who haven't been sent the relations, factions and colonies yet (added from the network thread). */
    private final Set<String> newClients = ConcurrentHashMap.newKeySet();
    private JSONObject lastRelations = new JSONObject();
    private float timer = 0f;
    private boolean started = false;

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
            //listed in this game's intel tab while they're connected (not the host's own: in this game that's the player)
            PlayerFactions.setShown(faction, !server.isLocalClient(clientId));
            server.registry.setLook(faction, look);
            if (message.has("reputation")) PlayerFactions.applyReputation(faction, message.getJSONObject("reputation"));
            if (message.has("blueprints")) PlayerFactions.applyBlueprints(faction, message.getJSONObject("blueprints"));
            server.broadcastExcept(clientId, lookPacket(faction, look, true).toString());
        } catch (JSONException e) {
            MultiplayerLog.log().error("Couldn't apply " + clientId + "'s faction", e);
        }
    }

    /**
     * A player's colonies, as their game describes them: mirrored here (except the host's own, real in this game),
     * kept for when they're offline, and sent to the other players.
     */
    void colonies(String clientId, JSONArray colonies) {
        String player = server.clientPlayers.get(clientId);
        String faction = server.clientFactions.get(clientId);
        if (player == null || !PlayerFactions.isSlot(faction)) return; //no faction of their own: their colonies can't be anyone's
        server.registry.setColonies(player, colonies);
        if (!server.isLocalClient(clientId)) ColonyMirrors.apply(player, faction, colonies, true); //the world: in its economy
        try {
            server.broadcastExcept(clientId, coloniesPacket(player, faction, colonies).toString());
        } catch (JSONException e) {
            MultiplayerLog.log().error("Couldn't send " + clientId + "'s colonies", e);
        }
    }

    /**
     * A player left: their faction stays theirs, with their reputation and colonies (the world keeps them while
     * they're offline); it's only no longer listed in the intel tab.
     */
    void freed(String faction) {
        if (!PlayerFactions.isSlot(faction)) return;
        PlayerFactions.setShown(faction, false);
        try {
            server.broadcast(lookPacket(faction, server.registry.look(faction), false).toString());
        } catch (JSONException e) {
            MultiplayerLog.log().error("Couldn't tell the clients " + faction + " left", e);
        }
    }

    /** Hosting stopped: nobody is connected (the players' leaving isn't processed once stopped). */
    void stopped() {
        newClients.clear();
        lastRelations = new JSONObject();
        started = false;
        PlayerFactions.hideAll();
    }

    /** Every frame, from ServerScripts. */
    void advance(float amount) {
        if (!started) {
            started = true;
            restoreOfflinePlayers();
        }
        try {
            if (!newClients.isEmpty()) {
                JSONObject all = PlayerFactions.worldRelations();
                for (String clientId : newClients.toArray(new String[0])) {
                    newClients.remove(clientId);
                    sendWorld(clientId, all);
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

    /** To a player who just joined: the relations, every player's faction (connected or not), and their colonies. */
    private void sendWorld(String clientId, JSONObject relations) throws JSONException {
        server.sendTo(clientId, relationsPacket(relations).toString());
        for (Map.Entry<String, String> faction : server.registry.factions().entrySet()) {
            boolean connected = server.clientFactions.containsValue(faction.getKey());
            server.sendTo(clientId, lookPacket(faction.getKey(), server.registry.look(faction.getKey()), connected).toString());
        }
        for (String player : server.registry.playersWithColonies()) {
            String faction = server.registry.faction(player);
            if (faction == null) continue;
            server.sendTo(clientId, coloniesPacket(player, faction, server.registry.colonies(player)).toString());
        }
    }

    /**
     * Hosting started: every player's faction looks as they last set it, and their colonies are mirrored as they
     * last described them (the world's save has the mirrors already; this catches anything it missed).
     */
    private void restoreOfflinePlayers() {
        for (String faction : server.registry.factions().keySet()) {
            PlayerFactions.applyLook(faction, server.registry.look(faction));
        }
        for (String player : server.registry.playersWithColonies()) {
            String faction = server.registry.faction(player);
            if (faction != null) ColonyMirrors.apply(player, faction, server.registry.colonies(player), true);
        }
    }

    private static JSONObject relationsPacket(JSONObject relations) throws JSONException {
        JSONObject packet = new JSONObject();
        packet.put("commandId", "factionRelations");
        packet.put("relations", relations);
        return packet;
    }

    /** How a player faction looks, and whether its player is connected (inUse: listed in the intel tab). */
    private static JSONObject lookPacket(String faction, JSONObject look, boolean inUse) throws JSONException {
        JSONObject packet = new JSONObject();
        packet.put("commandId", "playerFactionLook");
        packet.put("faction", faction);
        packet.put("inUse", inUse);
        if (look != null) packet.put("look", look);
        return packet;
    }

    private static JSONObject coloniesPacket(String player, String faction, JSONArray colonies) throws JSONException {
        JSONObject packet = new JSONObject();
        packet.put("commandId", "playerColonies");
        packet.put("player", player);
        packet.put("faction", faction);
        packet.put("colonies", colonies);
        return packet;
    }
}
