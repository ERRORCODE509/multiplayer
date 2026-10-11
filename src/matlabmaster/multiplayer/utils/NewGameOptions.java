package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.CharacterCreationData;
import matlabmaster.multiplayer.MultiplayerLog;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

/**
 * The new-game choices besides the seed that a player's game for a server is made with (client TitleJoin), so it
 * matches the server's world: the difficulty, and with Nexerelin its own options (NexerelinNewGame, only touched
 * when it's enabled: everything here works without it). Sent in the server's welcome as "newGame".
 */
public class NewGameOptions {
    public static final String NEXERELIN = "nexerelin";

    public static boolean nexerelin() {
        return Global.getSettings().getModManager().isModEnabled(NEXERELIN);
    }

    /** A new game was made (onNewGame): what it was made with that its save wouldn't otherwise say. */
    public static void recordNewWorld() {
        if (nexerelin()) NexerelinNewGame.record();
    }

    /** The server's world (hosting starts): {"difficulty", "nexerelin": its options, with Nexerelin}. */
    public static JSONObject describeWorld() {
        JSONObject options = new JSONObject();
        try {
            options.put("difficulty", Global.getSector().getDifficulty());
            if (nexerelin()) options.put(NEXERELIN, NexerelinNewGame.describeWorld());
        } catch (JSONException | RuntimeException | LinkageError e) {
            //another Nexerelin version, say: players' new games then use its defaults, with a warning on their side
            MultiplayerLog.log().error("Couldn't describe this world's new-game options for the players' new games", e);
        }
        return options;
    }

    /**
     * A player's new game (the agent's NewGameMaker, the game thread, after the stand-in start is set): the
     * server's choices. Null options: an older server, the defaults.
     */
    public static void apply(CharacterCreationData data, InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap, JSONObject options) {
        if (nexerelin()) {
            try {
                NexerelinNewGame.apply(data, dialog, memoryMap, options == null ? null : options.optJSONObject(NEXERELIN));
            } catch (RuntimeException | LinkageError e) {
                MultiplayerLog.log().error("Couldn't set Nexerelin's new-game options: the sector may not match the server's", e);
            }
        }
        //after Nexerelin's, which sets it from its own easy mode
        String difficulty = options == null ? null : options.optString("difficulty", null);
        if (difficulty != null) data.setDifficulty(difficulty);
    }
}
