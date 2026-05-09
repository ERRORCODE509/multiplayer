package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import matlabmaster.multiplayer.client.Client;
import org.json.JSONException;
import org.json.JSONObject;

public class ClockUtility {
    public static void sendServerTime(Client client) throws JSONException {
        if(client.isAuthority){
            JSONObject packet = new JSONObject();
            packet.put("commandId","handleServerTime");
            packet.put("timestamp", Global.getSector().getClock().getTimestamp());
            client.send(packet.toString());
        }
    }

    public static void setUiTime(Client client){
        client.ui.setServerTime(Global.getSector().getClock().getTimestamp());
    }
}
