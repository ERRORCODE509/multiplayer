package matlabmaster.multiplayer.server;

import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.updates.DebrisSync;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The world's battle debris fields (see DebrisSync), on the game thread: the ones players' games send are added
 * here and passed on; the ones this game makes itself (the host's own battles in "host current game") go to the
 * players; any gone here (salvaged by the host, or run out) is gone for them too; and a player who joins gets all
 * of them, made before they came or while they were away.
 */
public class ServerDebris {
    /** How often this game's fields are checked for new and gone ones (seconds). */
    private static final float INTERVAL = 1f;
    private final Server server;
    /** Players who haven't been sent the world's fields yet (added from the network thread). */
    private final Set<String> newClients = ConcurrentHashMap.newKeySet();
    /** The fields the players have been told of, by id. */
    private final Set<String> known = new HashSet<>();
    private boolean started = false;
    private float timer = 0f;

    ServerDebris(Server server) {
        this.server = server;
    }

    /** Network thread: a player joined. */
    void joined(String clientId) {
        newClients.add(clientId);
    }

    /** Hosting stopped. */
    void stopped() {
        newClients.clear();
        known.clear();
        started = false;
    }

    /** The debris a player's battle left ("debrisFields"), or a field gone in their game ("debrisGone"). */
    void received(String clientId, JSONObject message) throws JSONException {
        if ("debrisGone".equals(message.getString("commandId"))) {
            String id = message.getString("id");
            DebrisSync.remove(id);
            known.remove(id);
        } else {
            JSONObject fields = message.getJSONObject("fields");
            for (Iterator<?> it = fields.keys(); it.hasNext(); ) {
                String id = (String) it.next();
                DebrisSync.apply(id, fields.getJSONObject(id));
                //only if it's here: one that couldn't be added would otherwise be "gone" next check, from its own game too
                if (DebrisSync.exists(id)) known.add(id);
            }
        }
        server.broadcastExcept(clientId, message.toString());
    }

    /** Every frame, from ServerScripts. */
    void advance(float amount) {
        try {
            if (!started) {
                //the fields already here are sent to each player as they join
                started = true;
                timer = 0f;
                known.clear();
                for (Iterator<?> it = DebrisSync.allBattleFields().keys(); it.hasNext(); ) known.add((String) it.next());
            }
            if (!newClients.isEmpty()) {
                JSONObject all = DebrisSync.allBattleFields();
                for (String clientId : newClients.toArray(new String[0])) {
                    newClients.remove(clientId);
                    if (server.isLocalClient(clientId)) continue; //shares this game: has them all
                    JSONObject packet = new JSONObject();
                    packet.put("commandId", "debrisAll");
                    packet.put("fields", all);
                    server.sendTo(clientId, packet.toString());
                }
            }
            timer += amount;
            if (timer < INTERVAL) return;
            timer = 0f;
            JSONObject now = DebrisSync.allBattleFields();
            JSONObject fresh = new JSONObject();
            for (Iterator<?> it = now.keys(); it.hasNext(); ) {
                String id = (String) it.next();
                boolean isNew = known.add(id);
                //new here, or one a battle here just added to or ships were recovered from (the players' copies need it again)
                if (DebrisSync.changed(id, now.getJSONObject(id)) || isNew) fresh.put(id, now.get(id));
            }
            if (fresh.length() > 0) {
                JSONObject packet = new JSONObject();
                packet.put("commandId", "debrisFields");
                packet.put("fields", fresh);
                server.broadcastWorld(packet.toString());
                MultiplayerLog.log().info("Sent " + fresh.length() + " new or changed battle debris fields to the players");
            }
            for (Iterator<String> it = known.iterator(); it.hasNext(); ) {
                String id = it.next();
                if (now.has(id)) continue;
                it.remove();
                JSONObject packet = new JSONObject();
                packet.put("commandId", "debrisGone");
                packet.put("id", id);
                server.broadcastWorld(packet.toString());
            }
        } catch (JSONException e) {
            MultiplayerLog.log().error("Couldn't sync the world's debris fields", e);
        }
    }
}
