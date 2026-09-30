package matlabmaster.multiplayer.agent;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

/**
 * What the patched vanilla fleet managers call instead of Misc.getDistanceLY / Misc.getDistance. Vanilla measures
 * "distance to the player" to decide where AI fleets exist; with players published, a call where one side is the
 * game's own player fleet returns the distance to the nearest real player instead, so fleets spawn and stay around
 * every player at once. Any other call, or nothing published (single player, not hosting), is exactly vanilla.
 *
 * The multiplayer mod publishes the players' hyperspace positions each tick as a float[] {x0, y0, x1, y1, ...}
 * under POSITIONS_KEY in System.getProperties() (a JVM-wide object both sides can reach, so the mod never links
 * to this class). An empty array means nobody is connected: nothing is near any player.
 */
public class NearestPlayer {
    public static final String POSITIONS_KEY = "multiplayer.nearestPlayer.positions";
    /** Set by the agent at startup, so the mod can tell it's there. */
    public static final String ACTIVE_KEY = "multiplayer.nearestPlayer.active";
    private static final float NOBODY = 1e6f;

    public static float getDistanceLY(Vector2f a, Vector2f b) {
        float[] players = players();
        if (players != null) {
            Vector2f own = ownHyperspaceLocation();
            if (own != null) {
                if (same(a, own)) return nearest(players, b) / Misc.getUnitsPerLightYear();
                if (same(b, own)) return nearest(players, a) / Misc.getUnitsPerLightYear();
            }
        }
        return Misc.getDistanceLY(a, b);
    }

    public static float getDistanceLY(SectorEntityToken a, SectorEntityToken b) {
        float[] players = players();
        if (players != null) {
            CampaignFleetAPI own = Global.getSector() == null ? null : Global.getSector().getPlayerFleet();
            if (own != null && a == own && b != null) return nearest(players, b.getLocationInHyperspace()) / Misc.getUnitsPerLightYear();
            if (own != null && b == own && a != null) return nearest(players, a.getLocationInHyperspace()) / Misc.getUnitsPerLightYear();
        }
        return Misc.getDistanceLY(a, b);
    }

    /** Same as getDistanceLY in hyperspace units, for the managers that compare raw distances. */
    public static float getDistance(Vector2f a, Vector2f b) {
        float[] players = players();
        if (players != null) {
            Vector2f own = ownHyperspaceLocation();
            if (own != null) {
                if (same(a, own)) return nearest(players, b);
                if (same(b, own)) return nearest(players, a);
            }
        }
        return Misc.getDistance(a, b);
    }

    static float[] players() {
        Object o = System.getProperties().get(POSITIONS_KEY);
        return o instanceof float[] ? (float[]) o : null;
    }

    private static Vector2f ownHyperspaceLocation() {
        if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) return null;
        return Global.getSector().getPlayerFleet().getLocationInHyperspace();
    }

    private static boolean same(Vector2f p, Vector2f q) {
        return p != null && q != null && Math.abs(p.x - q.x) < 0.01f && Math.abs(p.y - q.y) < 0.01f;
    }

    static float nearest(float[] players, Vector2f p) {
        if (p == null) return NOBODY;
        float best = NOBODY;
        for (int i = 0; i + 1 < players.length; i += 2) {
            float dx = players[i] - p.x, dy = players[i + 1] - p.y;
            best = Math.min(best, (float) Math.sqrt(dx * dx + dy * dy));
        }
        return best;
    }
}
