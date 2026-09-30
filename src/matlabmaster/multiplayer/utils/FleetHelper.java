package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public class FleetHelper {
    public static void killAllFleetsExceptPlayer() {
        for (LocationAPI location : Global.getSector().getAllLocations()) {
            // Create a copy of the fleet list to avoid ConcurrentModificationException
            List<CampaignFleetAPI> fleetsCopy = new ArrayList<>(location.getFleets()); //needs a copy to avoid comodification exceptions
            for (CampaignFleetAPI fleet : fleetsCopy) {
                if (!fleet.isPlayerFleet() && !fleet.isStationMode()) {
                    fleet.despawn();
                }
            }
        }
    }
    public static JSONArray getFleetsSnapshot() throws JSONException {
        return getFleetsSnapshot(true);
    }

    /** Every fleet in the sector; includeOwnPlayerFleet false leaves this game's own player fleet out (a dedicated server's). */
    public static JSONArray getFleetsSnapshot(boolean includeOwnPlayerFleet) throws JSONException {
        JSONArray fleets = new JSONArray();
        for(LocationAPI location : Global.getSector().getAllLocations()){
            for (CampaignFleetAPI fleet : location.getFleets()){
                if (!includeOwnPlayerFleet && fleet.isPlayerFleet()) continue;
                if(!fleet.isStationMode()){ //sometimes stations are considered fleets
                    fleets.put(FleetSerializer.serializeFleet(fleet));
                }
            }
        }
        return fleets;
    }

    /** Every NPC fleet in the sector: the same fleets getNPCFleetsSnapshot() serializes. */
    public static List<CampaignFleetAPI> getNPCFleets() {
        List<CampaignFleetAPI> npcFleets = new ArrayList<>();
        for(LocationAPI location : Global.getSector().getAllLocations()){
            for (CampaignFleetAPI fleet : location.getFleets()){
                if(!fleet.isStationMode() && !fleet.isPlayerFleet() && !fleet.hasTag("playerFleet")){
                    npcFleets.add(fleet);
                }
            }
        }
        return npcFleets;
    }

    public static JSONArray getNPCFleetsSnapshot() throws JSONException {
        JSONArray fleets = new JSONArray();
        for(LocationAPI location : Global.getSector().getAllLocations()){
            for (CampaignFleetAPI fleet : location.getFleets()){
                if(!fleet.isStationMode() && !fleet.isPlayerFleet() && !fleet.hasTag("playerFleet")){ //sometimes stations are considered fleets
                    fleets.put(FleetSerializer.serializeFleet(fleet));
                }
            }
        }
        return fleets;
    }


    public static void removeFleetById(String id){
        SectorEntityToken entity = Global.getSector().getEntityById(id);
        //unknown id (never received, or already gone): nothing to remove, and never our own fleet
        if (entity == null || entity.getContainingLocation() == null || entity == Global.getSector().getPlayerFleet()) return;
        entity.getContainingLocation().removeEntity(entity);
    }

}
