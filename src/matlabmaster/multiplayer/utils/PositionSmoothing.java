package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A fleet copy moves by itself towards where it's headed (its move destination comes with every update), and the
 * position its game sends only corrects it when they've drifted apart. That correction used to put it there at
 * once, a visible jump; now it's made up over a fraction of a second, on top of its own movement. Far off (a
 * teleport, a lost update) it's still put there at once. Every game: players' and the server's copies alike.
 */
public class PositionSmoothing {
    /** Closer than this a copy is left alone (as before): about what the network delay puts it behind. */
    private static final float DEADBAND = 50f;
    /** Further than this it's put there at once. */
    private static final float SNAP = 500f;
    /** How much of what's left is made up per second (about 95% within 0.6 s). */
    private static final float RATE = 5f;

    /** What's still to be made up, per copy (game thread only). */
    private static final Map<CampaignFleetAPI, Vector2f> offsets = new IdentityHashMap<>();

    /** Where a copy's game has it on one axis (the other NaN): from a fleet update. */
    public static void toward(CampaignFleetAPI fleet, float x, float y) {
        Vector2f at = fleet.getLocation();
        Vector2f offset = offsets.computeIfAbsent(fleet, f -> new Vector2f());
        if (!Float.isNaN(x)) offset.x = correction(fleet, x - at.x, true);
        if (!Float.isNaN(y)) offset.y = correction(fleet, y - at.y, false);
        if (offset.x == 0f && offset.y == 0f) offsets.remove(fleet);
    }

    /** The part of an axis' error to make up gradually (0 if none, or if it was just made up at once). */
    private static float correction(CampaignFleetAPI fleet, float error, boolean xAxis) {
        if (Math.abs(error) <= DEADBAND) return 0f;
        if (Math.abs(error) > SNAP) {
            Vector2f at = fleet.getLocation();
            if (xAxis) fleet.setLocation(at.x + error, at.y);
            else fleet.setLocation(at.x, at.y + error);
            return 0f;
        }
        return error;
    }

    /** A copy moved to another location (or was replaced): nothing left to make up. */
    public static void forget(CampaignFleetAPI fleet) {
        offsets.remove(fleet);
    }

    /** A new game was loaded: the copies of the last one are gone. */
    public static void clear() {
        offsets.clear();
    }

    /** Every frame (ClientScripts, which every game runs). */
    public static void advance(float amount) {
        if (offsets.isEmpty()) return;
        float share = Math.min(1f, RATE * amount);
        for (Iterator<Map.Entry<CampaignFleetAPI, Vector2f>> it = offsets.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<CampaignFleetAPI, Vector2f> entry = it.next();
            CampaignFleetAPI fleet = entry.getKey();
            Vector2f offset = entry.getValue();
            if (fleet.getContainingLocation() == null || !fleet.isAlive()) { //gone
                it.remove();
                continue;
            }
            float dx = offset.x * share, dy = offset.y * share;
            fleet.setLocation(fleet.getLocation().x + dx, fleet.getLocation().y + dy);
            offset.x -= dx;
            offset.y -= dy;
            if (Math.abs(offset.x) < 1f && Math.abs(offset.y) < 1f) it.remove();
        }
    }
}
