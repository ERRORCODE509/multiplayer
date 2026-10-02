package matlabmaster.multiplayer.server;

import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.updates.WorldBounties;
import matlabmaster.multiplayer.updates.WorldOwnership;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The world's side of WorldOwnership and the bounty board (game thread): every few seconds, what changed hands
 * and the open bounties (when they changed) are sent to every player; a player who joins gets all of it.
 */
public class ServerOwnership {
    private static final float INTERVAL = 5f;

    private final Server server;
    private float timer = 0f;
    /** What was last sent (null: nothing yet this session). */
    private JSONObject last;
    /** The open bounties as last sent (WorldBounties.board). */
    private String lastBounties;

    ServerOwnership(Server server) {
        this.server = server;
    }

    /** Every frame (ServerScripts). */
    void advance(float amount) {
        timer += amount;
        if (timer < INTERVAL) return;
        timer = 0f;
        try {
            JSONObject now = WorldOwnership.describe();
            JSONObject changes = last == null ? null : WorldOwnership.changes(last, now);
            last = now;
            if (changes != null) server.broadcastWorld(new JSONObject().put("commandId", "worldOwnership").put("state", changes).toString());
            //the open bounties too, when they change (posted, claimed, a day less)
            String bounties = WorldBounties.board().toString();
            if (!bounties.equals(lastBounties)) {
                lastBounties = bounties;
                server.broadcastWorld(new JSONObject().put("commandId", "worldBounties").put("bounties", new JSONArray(bounties)).toString());
            }
        } catch (JSONException e) {
            MultiplayerLog.log().error("Couldn't send who owns the world's markets", e);
        }
    }

    /** A player joined: everything (game thread, see Server.hello). */
    void joined(String clientId) {
        try {
            server.sendTo(clientId, new JSONObject().put("commandId", "worldOwnership").put("state", WorldOwnership.describe()).toString());
            server.sendTo(clientId, new JSONObject().put("commandId", "worldBounties").put("bounties", WorldBounties.board()).toString());
        } catch (JSONException e) {
            MultiplayerLog.log().error("Couldn't send who owns the world's markets to " + clientId, e);
        }
    }

    void stopped() {
        last = null;
        lastBounties = null;
        timer = 0f;
    }
}
