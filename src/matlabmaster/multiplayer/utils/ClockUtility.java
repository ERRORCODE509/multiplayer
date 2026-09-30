package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import org.json.JSONException;
import org.json.JSONObject;

public class ClockUtility {
    /** The world's time, sent by the server's game (the only authority) to every client each tick. */
    public static JSONObject serverTimePacket() throws JSONException {
        JSONObject packet = new JSONObject();
        packet.put("commandId","handleServerTime");
        packet.put("timestamp", Global.getSector().getClock().getTimestamp());
        return packet;
    }
}
