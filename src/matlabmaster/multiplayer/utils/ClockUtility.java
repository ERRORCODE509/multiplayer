package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.campaign.CampaignClock;
import org.json.JSONException;
import org.json.JSONObject;

public class ClockUtility {
    /** Beyond this a client's clock is set straight to the server's (game time, ms): one game hour. */
    public static final long MAX_DRIFT_MS = 60L * 60L * 1000L;
    /** Below this the drift is left alone (game time, ms): about what a frame or two of lag makes. */
    private static final long DEADBAND_MS = 5L * 60L * 1000L;
    /** The share of a smaller drift made up per server time update (20 a second): gone in about a second, unseen. */
    private static final float NUDGE = 0.1f;

    /** The world's time, sent by the server's game (the only authority) to every client each tick. */
    public static JSONObject serverTimePacket() throws JSONException {
        JSONObject packet = new JSONObject();
        packet.put("commandId","handleServerTime");
        packet.put("timestamp", Global.getSector().getClock().getTimestamp());
        return packet;
    }

    /**
     * Keeps a client's clock on the server's time: more than MAX_DRIFT_MS apart (the host fast-forwarded, a dialog
     * stopped this game) it's set to it at once; less than that it's nudged towards it a little at every update,
     * rather than jumping an hour every few seconds as the client ran slow; within DEADBAND_MS it's left alone, so
     * the clock doesn't jitter. The API has no clock setter; the game's clock class is marked DoNotObfuscate, so its
     * advance(realSeconds) is stable across game versions, and it takes negative values too. Returns the correction
     * applied, in game milliseconds (0 if none).
     */
    public static long syncToServer(CampaignClockAPI clock, long serverTimestamp) {
        long drift = serverTimestamp - clock.getTimestamp();
        if (Math.abs(drift) <= DEADBAND_MS || !(clock instanceof CampaignClock)) return 0;
        long correction = Math.abs(drift) > MAX_DRIFT_MS ? drift : (long) (drift * NUDGE);
        float gameDays = correction / (1000f * 60f * 60f * 24f);
        ((CampaignClock) clock).advance(gameDays * clock.getSecondsPerDay());
        return correction;
    }
}
