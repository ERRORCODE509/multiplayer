package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.campaign.CampaignClock;
import org.json.JSONException;
import org.json.JSONObject;

public class ClockUtility {
    /** How far a client's clock may drift from the server's before it's corrected (game time, ms): one game hour. */
    public static final long MAX_DRIFT_MS = 60L * 60L * 1000L;

    /** The world's time, sent by the server's game (the only authority) to every client each tick. */
    public static JSONObject serverTimePacket() throws JSONException {
        JSONObject packet = new JSONObject();
        packet.put("commandId","handleServerTime");
        packet.put("timestamp", Global.getSector().getClock().getTimestamp());
        return packet;
    }

    /**
     * Brings a client's clock to the server's time when they're more than MAX_DRIFT_MS apart (small drift from
     * frame timing is left alone, so the clock doesn't jitter). The API has no clock setter; the game's clock
     * class is marked DoNotObfuscate, so its advance(realSeconds) is stable across game versions, and it takes
     * negative values too. Returns the correction applied, in game milliseconds (0 if none).
     */
    public static long syncToServer(CampaignClockAPI clock, long serverTimestamp) {
        long drift = serverTimestamp - clock.getTimestamp();
        if (Math.abs(drift) <= MAX_DRIFT_MS || !(clock instanceof CampaignClock)) return 0;
        float gameDays = drift / (1000f * 60f * 60f * 24f);
        ((CampaignClock) clock).advance(gameDays * clock.getSecondsPerDay());
        return drift;
    }
}
