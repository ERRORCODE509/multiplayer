package matlabmaster.multiplayer.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.input.Mouse;

/**
 * In single player a dialog pauses the game; here the world runs on, so what the player was talking to (a planet,
 * station, derelict...) keeps orbiting, and once the dialog closes the orbits catch up with the server's: without
 * this the player would be left where the planet was. While they talk to something that moves, their fleet orbits
 * it (where it is, practically standing still), so it stays at its side; the player's next click in the campaign
 * (how vanilla gives move orders) lets go.
 */
public class InteractionOrbit {
    /** Days for the fleet to go around: it keeps its place by the target rather than visibly circling it. */
    private static final float PERIOD = 100000f;
    private SectorEntityToken focus;
    private boolean dialogOver = false;

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
        //headed where it is: the orbit moves it, and the world's copy (which goes where we're headed between our
        //updates) would otherwise keep floating back to wherever we were headed before
        fleet.setMoveDestination(fleet.getLocation().x, fleet.getLocation().y);
        if (Global.getSector().getCampaignUI().isShowingDialog()) {
            dialogOver = false; //still talking
            return;
        }
        if (!dialogOver) {
            dialogOver = !Mouse.isButtonDown(0); //the click that closed the dialog doesn't count
            return;
        }
        if (Mouse.isButtonDown(0)) {
            fleet.setOrbit(null);
            focus = null;
        }
    }

    /** A game loaded: any orbit of ours was in the previous one. */
    public void forget() {
        focus = null;
        dialogOver = false;
    }
}
