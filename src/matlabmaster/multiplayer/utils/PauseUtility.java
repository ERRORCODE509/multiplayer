package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.client.Client;
import matlabmaster.multiplayer.client.ClientMarkets;
import matlabmaster.multiplayer.updates.FleetSync;
import matlabmaster.multiplayer.updates.WorldSync;
import org.json.JSONException;
import org.json.JSONObject;

public class PauseUtility {
    public static void clientPauseUtility(Client client, FleetSync fleetSync, ClientMarkets markets){
        try {
            if(Global.getSector().isPaused()){
                if(!Global.getSector().getCampaignUI().isShowingDialog()){
                    Global.getSector().setPaused(false);
                }else if(!client.wasPaused){
                    client.wasPaused = true;
                    JSONObject packet = new JSONObject();
                    packet.put("commandId","paused");
                    //the fleet we're talking to: the world doesn't pause for us, so the server holds it still
                    InteractionDialogAPI dialog = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
                    SectorEntityToken target = dialog == null ? null : dialog.getInteractionTarget();
                    if (target instanceof CampaignFleetAPI && target != Global.getSector().getPlayerFleet()) {
                        packet.put("interactionTarget", target.getId());
                    }
                    markets.dialogOpened(client, target); //at a market: the server's stock

                    //name update
                    String fleetName = Global.getSector().getPlayerFleet().getName();
                    fleetName += " [PAUSED]";//9 char long
                    Global.getSector().getPlayerFleet().setName(fleetName);
                    fleetSync.sendOwnFleetUpdate(client);//send the updated name

                    client.send(String.valueOf(packet));
                }
            }else{
                if(client.wasPaused){
                    client.wasPaused = false;
                    JSONObject packet = new JSONObject();
                    packet.put("commandId","unpaused");
                    String fleetName = Global.getSector().getPlayerFleet().getName();
                    // Check if it ends with " [paused]" and remove it if present
                    if (fleetName.endsWith(" [PAUSED]")) {
                        fleetName = fleetName.substring(0, fleetName.length() - " [paused]".length());
                        Global.getSector().getPlayerFleet().setName(fleetName);
                    }

                    //catch up with the various updates
                    if(!client.isSelfHosted){ //the host's own game is the world
                        WorldSync.requestOrbitSnapshotForLocation(Global.getSector().getPlayerFleet().getContainingLocation(),client);
                    }
                    markets.dialogClosed(client); //what we bought and sold, to the server's market


                    client.send(packet.toString());
                }
            }
        }catch (JSONException e){
            MultiplayerLog.log().error("Error in the pause utility", e);
        }
    }
}
