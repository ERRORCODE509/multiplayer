package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A fleet copy flies by itself, with its game's velocity and towards its destination (both come with every update),
 * and the position its game sends corrects what's left: a little of the gap at every frame, both axes as one, so it
 * converges smoothly instead of jumping. It used to be put there at once, then corrected axis by axis only beyond 50
 * units, which stuttered, mostly sideways to its course (that gap hovered around the threshold). Far off (a
 * teleport, a lost update) it's still put there at once. Every game: players' and the server's copies alike. The
 * same for planets and stations when their orbits are resynced (clients only).
 */
public class PositionSmoothing {
    /** Closer than this a copy is left alone: nothing to see. */
    private static final float DEADBAND = 2f;
    /** Further than this it's put there at once. */
    private static final float SNAP = 500f;
    /** How much of what's left is made up per second (about 95% within 0.6 s; the gap is renewed 20 times a second). */
    private static final float RATE = 5f;

    /** What's still to be made up, per copy (game thread only). */
    private static final Map<CampaignFleetAPI, Vector2f> offsets = new IdentityHashMap<>();
    /** Orbits (planets, stations...) are resynced every few seconds: closer than this (degrees) they're left alone. */
    private static final float ORBIT_DEADBAND = 0.05f;
    /** Further than this (degrees) an orbit is put there at once. */
    private static final float ORBIT_SNAP = 20f;
    /** The degrees still to be made up, per orbiting entity (game thread only). */
    private static final Map<SectorEntityToken, Float> orbitOffsets = new IdentityHashMap<>();

    /** Where a copy's game has it (from a fleet update, same location). */
    public static void toward(CampaignFleetAPI fleet, float x, float y) {
        float dx = x - fleet.getLocation().x, dy = y - fleet.getLocation().y;
        float gap = (float) Math.sqrt(dx * dx + dy * dy);
        if (gap > SNAP) {
            offsets.remove(fleet);
            fleet.setLocation(x, y);
        } else if (gap < DEADBAND) {
            offsets.remove(fleet);
        } else {
            offsets.computeIfAbsent(fleet, f -> new Vector2f()).set(dx, dy);
        }
    }

    /**
     * Where the server has something on its circular orbit: it's moved there along the orbit, over a fraction of a
     * second like a fleet, rather than jumping (a degree on a wide orbit is a hundred units and more).
     */
    public static void orbitToward(SectorEntityToken entity, float angle) {
        float delta = Misc.getAngleDiff(entity.getCircularOrbitAngle(), angle) * Misc.getClosestTurnDirection(entity.getCircularOrbitAngle(), angle);
        if (Math.abs(delta) <= ORBIT_DEADBAND) {
            orbitOffsets.remove(entity);
        } else if (Math.abs(delta) > ORBIT_SNAP) {
            orbitOffsets.remove(entity);
            entity.setCircularOrbitAngle(angle);
        } else {
            orbitOffsets.put(entity, delta);
        }
    }

    /** A copy moved to another location (or was replaced): nothing left to make up. */
    public static void forget(CampaignFleetAPI fleet) {
        offsets.remove(fleet);
    }

    /** A new game was loaded: the copies of the last one are gone. */
    public static void clear() {
        offsets.clear();
        orbitOffsets.clear();
    }

    /** Every frame (ClientScripts, which every game runs). */
    public static void advance(float amount) {
        float share = Math.min(1f, RATE * amount);
        for (Iterator<Map.Entry<SectorEntityToken, Float>> it = orbitOffsets.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<SectorEntityToken, Float> entry = it.next();
            SectorEntityToken entity = entry.getKey();
            if (entity.getContainingLocation() == null || entity.getOrbit() == null) { //gone, or let go of its orbit
                it.remove();
                continue;
            }
            float step = entry.getValue() * share;
            entity.setCircularOrbitAngle(entity.getCircularOrbitAngle() + step);
            float left = entry.getValue() - step;
            if (Math.abs(left) < ORBIT_DEADBAND) it.remove();
            else entry.setValue(left);
        }
        if (offsets.isEmpty()) return;
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
