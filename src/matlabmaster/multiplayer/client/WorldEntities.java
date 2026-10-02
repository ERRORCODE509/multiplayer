package matlabmaster.multiplayer.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.updates.EntitySync;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * A client's side of EntitySync (game thread). Only what the world has listed is ever touched: this game may have
 * salvageable things of its own (a mission's), which the world never hears of and which stay. Of those:
 * - listed before and no longer (someone salvaged it, or it expired in the world): removed here;
 * - listed and here, then gone from here (our player salvaged it): the server is told.
 */
public class WorldEntities {
    /** What the world has listed this session, id -> its location. */
    private final Map<String, String> seen = new HashMap<>();
    /** Listed by the world and here too: if it goes from here, it was salvaged here. */
    private final Set<String> confirmed = new HashSet<>();

    /** The world's salvageable things where our fleet is (every few seconds). */
    public void listed(Client client, String locationId, JSONArray idsJson) throws JSONException {
        CampaignFleetAPI own = Global.getSector().getPlayerFleet();
        checkSalvaged(client); //first: anything we salvaged since (here or where we were) the world has to hear of
        if (own == null || own.getContainingLocation() == null || !own.getContainingLocation().getId().equals(locationId)) return;
        Set<String> world = EntitySync.fromJson(idsJson);
        Set<String> here = EntitySync.idsIn(own.getContainingLocation());
        for (String id : world) {
            seen.put(id, locationId);
            if (here.contains(id)) confirmed.add(id);
        }
        int removed = 0;
        for (String id : here) {
            if (world.contains(id) || !locationId.equals(seen.get(id))) continue;
            //the world had it and doesn't any more
            seen.remove(id);
            confirmed.remove(id);
            if (EntitySync.remove(id)) removed++;
        }
        if (removed > 0) MultiplayerLog.log().info(removed + " salvageable things here are gone from the world (salvaged by someone else): gone here too");
    }

    /** Ours the world had that are gone from here: salvaged here. */
    private void checkSalvaged(Client client) {
        for (Iterator<String> it = confirmed.iterator(); it.hasNext(); ) {
            String id = it.next();
            SectorEntityToken entity = Global.getSector().getEntityById(id);
            if (EntitySync.isPresent(entity)) continue;
            it.remove();
            seen.remove(id);
            try {
                client.send(new JSONObject().put("commandId", "entityGone").put("id", id).toString());
                MultiplayerLog.log().info("Salvaged " + (entity == null ? id : entity.getName()) + ": the world hears of it");
            } catch (JSONException e) {
                MultiplayerLog.log().error("Couldn't tell the server " + id + " was salvaged", e);
            }
        }
    }

    /** Someone else salvaged one (worldEntityGone), or a list of all salvaged so far on joining (worldEntitiesGone). */
    public void gone(Iterable<String> ids) {
        int removed = 0;
        for (String id : ids) {
            seen.remove(id);
            confirmed.remove(id);
            if (EntitySync.remove(id)) removed++;
        }
        if (removed > 0) MultiplayerLog.log().info(removed + " salvageable things were salvaged in the world: gone here too");
    }

    /** Left the server or loaded another game. */
    public void reset() {
        seen.clear();
        confirmed.clear();
    }
}
