package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import matlabmaster.multiplayer.client.ClientScripts;
import matlabmaster.multiplayer.server.ServerScripts;

import java.util.ArrayList;
import java.util.List;

/**
 * Takes the sector's scripts (economy, bounties, other mods' managers...) out while this client is not the
 * authority, and puts them back. Anything taken out must always be put back before the game is saved or the
 * client leaves, otherwise the scripts are lost from the player's save for good.
 */
public class SectorScriptsUtility {
    private final List<EveryFrameScript> savedScripts = new ArrayList<>();

    public void disableScripts(){
        //copy first so the sector's list is never modified while being iterated
        for (EveryFrameScript script : new ArrayList<>(Global.getSector().getScripts())) {
            if (script instanceof ClientScripts || script instanceof ServerScripts) continue; //never remove our own scripts
            if (isGameMechanic(script)) continue;
            if (!savedScripts.contains(script)) {
                savedScripts.add(script); //also catches scripts added since the last call
            }
            Global.getSector().removeScript(script);
        }
    }

    /**
     * The game core's own scripts (not the API's, not mods'): mechanics of this player's own fleet that the core adds
     * as sector scripts, like the jump through a jump point (CampaignEngine.doHyperspaceTransition), drifting with
     * no fuel, and sensor ping visuals. Taking them out left a jumping fleet stuck in the transition for good.
     */
    private static boolean isGameMechanic(EveryFrameScript script) {
        return script.getClass().getName().startsWith("com.fs.starfarer.campaign.");
    }

    public void restoreScripts(){
        if (savedScripts.isEmpty()) return;
        List<EveryFrameScript> current = Global.getSector().getScripts();
        for (EveryFrameScript script : savedScripts) {
            if (!current.contains(script)) {
                Global.getSector().addScript(script);
            }
        }
        savedScripts.clear();
    }

    /**
     * Drops scripts saved from a previous game without restoring them, for when another game is loaded
     * (they belong to the old sector, which is gone).
     */
    public void forgetScripts(){
        savedScripts.clear();
    }
}
