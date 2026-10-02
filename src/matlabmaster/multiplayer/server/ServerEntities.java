package matlabmaster.multiplayer.server;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.updates.EntitySync;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

/**
 * The world's side of EntitySync (game thread): every few seconds each player is told what salvageable things the
 * world has where they are; a player who salvaged one has it removed here and everywhere. The ones gone are kept
 * in the world's save ("entitiesGone" in the PlayerRegistry), for players who join after.
 */
public class ServerEntities {
    private static final float INTERVAL = 3f;
    private static final String GONE = "entitiesGone";

    private final Server server;
    private float timer = 0f;

    ServerEntities(Server server) {
        this.server = server;
    }

    /** Every frame (ServerScripts). */
    void advance(float amount) {
        timer += amount;
        if (timer < INTERVAL) return;
        timer = 0f;
        for (Map.Entry<String, java.util.function.Consumer<String>> client : server.worldClients().entrySet()) {
            SectorEntityToken fleet = Global.getSector().getEntityById(client.getKey()); //this game's copy of their fleet
            if (!(fleet instanceof CampaignFleetAPI) || fleet.getContainingLocation() == null) continue;
            try {
                JSONObject packet = new JSONObject();
                packet.put("commandId", "worldEntities");
                packet.put("location", fleet.getContainingLocation().getId());
                packet.put("ids", EntitySync.toJson(EntitySync.idsIn(fleet.getContainingLocation())));
                client.getValue().accept(packet.toString());
            } catch (JSONException e) {
                MultiplayerLog.log().error("Couldn't tell " + client.getKey() + " what's salvageable there", e);
            }
        }
    }

    /** A player salvaged one of the world's things in their game: gone here and for everyone. */
    void gone(String clientId, String id) {
        SectorEntityToken entity = Global.getSector().getEntityById(id);
        String name = entity == null ? id : entity.getName();
        boolean removed = EntitySync.remove(id);
        remember(id);
        if (!removed) return; //gone here already (two players at once, or it expired)
        MultiplayerLog.log().info(server.who(clientId) + " salvaged " + name + ": gone from the world");
        try {
            JSONObject packet = new JSONObject().put("commandId", "worldEntityGone").put("id", id);
            for (Map.Entry<String, java.util.function.Consumer<String>> client : server.worldClients().entrySet()) {
                if (!client.getKey().equals(clientId)) client.getValue().accept(packet.toString());
            }
        } catch (JSONException e) {
            MultiplayerLog.log().error("Couldn't tell the players " + name + " is gone", e);
        }
    }

    private void remember(String id) {
        JSONArray gone = all();
        for (int i = 0; i < gone.length(); i++) {
            if (id.equals(gone.optString(i))) return;
        }
        gone.put(id);
        server.registry.put(GONE, gone.toString());
    }

    private JSONArray all() {
        String stored = server.registry.get(GONE);
        try {
            return stored == null ? new JSONArray() : new JSONArray(stored);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    /** A player joined (network thread): everything salvaged from the world so far, for their game to remove. */
    void joined(String clientId) {
        JSONArray gone = all();
        if (gone.length() == 0) return;
        try {
            server.sendTo(clientId, new JSONObject().put("commandId", "worldEntitiesGone").put("ids", gone).toString());
        } catch (JSONException e) {
            MultiplayerLog.log().error("Couldn't tell " + clientId + " what was salvaged", e);
        }
    }

    void stopped() {
        timer = 0f;
    }
}
