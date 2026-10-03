package matlabmaster.multiplayer;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.input.InputEventAPI;

import java.util.List;
import java.util.function.Function;

/**
 * Loads the world's save straight from the title screen when the multiplayer launcher starts a server instance
 * (-Dmultiplayer.autoLoadSave=<save folder>): nobody plays there, so nobody should have to click Load. The title
 * screen's background battle runs combat plugins every frame, which is the moment to do it from (the game's
 * thread, the title screen up); the multiplayer agent does the loading (SaveLoader: the mod can't), once.
 * Registered in data/config/settings.json; does nothing in any other game.
 */
public class AutoLoadPlugin extends BaseEveryFrameCombatPlugin {
    public static final String SAVE_KEY = "multiplayer.autoLoadSave";
    private static final String LOAD_KEY = "multiplayer.loadSave";
    /** Seconds of title screen first: its own setup (and the music, the background battle) settles. */
    private static final float DELAY = 1f;
    /** Once per run of the game: a failed load leaves the title screen to the player. */
    private static boolean tried = false;
    private float waited = 0f;

    @Override
    @SuppressWarnings("unchecked")
    public void advance(float amount, List<InputEventAPI> events) {
        if (tried || Global.getCurrentState() != GameState.TITLE) return;
        String save = System.getProperty(SAVE_KEY);
        if (save == null || save.isEmpty()) {
            tried = true;
            return;
        }
        waited += amount;
        if (waited < DELAY) return;
        tried = true;
        Object loader = System.getProperties().get(LOAD_KEY);
        if (!(loader instanceof Function)) {
            MultiplayerLog.log().warn("Can't load " + save + " by itself: the multiplayer agent isn't running (start the server with the launcher)");
            return;
        }
        MultiplayerLog.log().info("Loading the world's save " + save + " (server instance)");
        String error = ((Function<String, String>) loader).apply(save);
        if (error != null) MultiplayerLog.log().error("Couldn't load " + save + " by itself: " + error + " (load it from the title screen)");
    }
}
