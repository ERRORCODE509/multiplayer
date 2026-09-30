package matlabmaster.multiplayer.updates;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * Whether a client may be sent an NPC fleet: judged on the server from its copy of that client's fleet, with the
 * game's own detection maths (getMaxSensorRangeToDetect: the observer's sensors against the target's profile, so
 * Go Dark, transponders, nebulae and sensor bursts all count). The client's own game still decides what it shows;
 * this only has to include everything it could show, so it's generous going in, and a fleet already sent is only
 * withdrawn well past that range, so fleets at the edge of sensors don't flicker in and out.
 */
public class VisibleFleets {
    public static final float ENTER_MULT = 1.25f;
    public static final float ENTER_PAD = 500f;
    public static final float LEAVE_MULT = 1.5f;
    public static final float LEAVE_PAD = 1500f;

    public static boolean canSee(CampaignFleetAPI observer, CampaignFleetAPI target, boolean alreadySent) {
        if (observer == null || target == null) return false;
        if (observer.getContainingLocation() == null || observer.getContainingLocation() != target.getContainingLocation()) return false;
        float range = observer.getMaxSensorRangeToDetect(target);
        float limit = alreadySent ? range * LEAVE_MULT + LEAVE_PAD : range * ENTER_MULT + ENTER_PAD;
        return Misc.getDistance(observer.getLocation(), target.getLocation()) <= limit;
    }
}
