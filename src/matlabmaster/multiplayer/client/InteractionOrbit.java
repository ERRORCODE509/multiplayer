package matlabmaster.multiplayer.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

/**
 * In single player a dialog pauses the game; here the world runs on, so what the player was talking to (a planet,
 * station, derelict...) keeps orbiting, and once the dialog closes the orbits catch up with the server's: without
 * this the player would be left where the planet was. While they talk to something that moves, their fleet orbits
 * it (where it is, practically standing still), so it stays at its side; the player's next move order lets go.
 */
public class InteractionOrbit {
    /** Days for the fleet to go around: it keeps its place by the target rather than visibly circling it. */
    private static final float PERIOD = 100000f;
    private SectorEntityToken focus;
    private boolean dialogOver = false;
    /** Where advance() last pointed the fleet: a move order changes it (any click let go before, UI buttons too). */
    private Vector2f headedTo;
    /** How far the destination may be from that before it's a move order (the orbit moves the fleet far less a frame). */
    private static final float MOVE_ORDER = 10f;

    /** A dialog opened with this target (PauseUtility): follow it if it moves. */
    public void start(SectorEntityToken target) {
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        if (focus != null && fleet != null && fleet.getOrbitFocus() == focus) fleet.setOrbit(null); //ours from before
        forget();
        if (fleet == null || target == null || target == fleet || target instanceof CampaignFleetAPI) return; //fleets the server holds
        if (target instanceof JumpPointAPI) return; //talking to one ends in a jump: the fleet must be free to go
        if (target.getOrbit() == null || target.getContainingLocation() != fleet.getContainingLocation()) return;
        float angle = Misc.getAngleInDegrees(target.getLocation(), fleet.getLocation());
        float radius = Misc.getDistance(target.getLocation(), fleet.getLocation());
        fleet.setCircularOrbit(target, angle, radius, PERIOD);
        focus = target;
    }

    /** Every frame (ClientScripts): lets go when the player moves (or anything else took the fleet out of this orbit). */
    public void advance() {
        if (focus == null) return;
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        if (fleet == null || fleet.getOrbitFocus() != focus) {
            focus = null; //not ours any more (another orbit): leave it alone
            return;
        }
        if (fleet.isInHyperspaceTransition() || fleet.getContainingLocation() != focus.getContainingLocation()) {
            fleet.setOrbit(null); //jumping, or gone elsewhere: an orbit around something left behind would drag it back
            focus = null;
            return;
        }
        //the player sent the fleet somewhere since last frame (after the dialog): it's theirs again
        if (dialogOver && headedTo != null && Misc.getDistance(fleet.getMoveDestination(), headedTo) > MOVE_ORDER) {
            fleet.setOrbit(null);
            focus = null;
            headedTo = null;
            return;
        }
        dialogOver = !Global.getSector().getCampaignUI().isShowingDialog();
        //headed where it is: the orbit moves it, and the world's copy (which goes where we're headed between our
        //updates) would otherwise keep floating back to wherever we were headed before
        fleet.setMoveDestination(fleet.getLocation().x, fleet.getLocation().y);
        headedTo = new Vector2f(fleet.getLocation());
    }

    /** A game loaded: any orbit of ours was in the previous one. */
    public void forget() {
        focus = null;
        dialogOver = false;
        headedTo = null;
    }
}
