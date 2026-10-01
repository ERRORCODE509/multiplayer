package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.client.Client;
import matlabmaster.multiplayer.client.ClientMarkets;
import matlabmaster.multiplayer.client.InteractionOrbit;
import matlabmaster.multiplayer.updates.FleetSync;
import matlabmaster.multiplayer.updates.WorldSync;
import org.json.JSONException;
import org.json.JSONObject;

public class PauseUtility {
    private static final String PAUSED = " [PAUSED]";

    /**
     * Our fleet's name without the dialog mark. Also when we leave the server (or it goes) mid-dialog, and on
     * loading a game: the mark was otherwise kept, in the save too, and added again next time.
     */
    public static void clearPausedName() {
        if (Global.getSector().getPlayerFleet() == null) return;
        String fleetName = Global.getSector().getPlayerFleet().getName();
        while (fleetName != null && fleetName.endsWith(PAUSED)) {
            fleetName = fleetName.substring(0, fleetName.length() - PAUSED.length());
            Global.getSector().getPlayerFleet().setName(fleetName);
        }
    }

    public static void clientPauseUtility(Client client, FleetSync fleetSync, ClientMarkets markets, InteractionOrbit orbit){
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
                    //what we're talking to, and where we see it: the server holds a fleet there, and keeps our
                    //fleet by a planet or station as this game does
                    if (target != null && target != Global.getSector().getPlayerFleet() && target.getId() != null) {
                        packet.put("interactionTarget", target.getId());
                        packet.put("targetX", target.getLocation().x);
                        packet.put("targetY", target.getLocation().y);
                        //and where we are: our fleet stops there in the server's game too (we send no movement meanwhile)
                        packet.put("selfX", Global.getSector().getPlayerFleet().getLocation().x);
                        packet.put("selfY", Global.getSector().getPlayerFleet().getLocation().y);
                    }
                    markets.dialogOpened(client, target); //at a market: the server's stock
                    if (!client.isSelfHosted) orbit.start(target); //it moves on while we talk: stay by it

                    //name update
                    Global.getSector().getPlayerFleet().setName(Global.getSector().getPlayerFleet().getName() + PAUSED);
                    fleetSync.sendOwnFleetUpdate(client);//send the updated name

                    client.send(String.valueOf(packet));
                }
            }else{
                if(client.wasPaused){
                    client.wasPaused = false;
                    JSONObject packet = new JSONObject();
                    packet.put("commandId","unpaused");
                    clearPausedName();

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
