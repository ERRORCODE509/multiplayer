package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * What has to match between the host's game and a joiner's: the game version, the save's seed (the sector
 * itself isn't sent, each player generates it from the seed) and the enabled mods. Utility mods are left
 * out: they are declared not to change the game world, so players may have different ones.
 * Only reads values that are fixed once a game is loaded, so it may be called from any thread.
 */
public class CompatibilityUtility {

    public static JSONObject describeThisGame() throws JSONException {
        JSONObject game = new JSONObject();
        game.put("version", Global.getSettings().getVersionString());
        game.put("seed", Global.getSector().getSeedString());
        JSONObject mods = new JSONObject();
        for (ModSpecAPI mod : Global.getSettings().getModManager().getEnabledModsCopy()) {
            if (mod.isUtility()) continue;
            JSONObject info = new JSONObject();
            info.put("name", mod.getName());
            info.put("version", mod.getVersion());
            mods.put(mod.getId(), info);
        }
        game.put("mods", mods);
        return game;
    }

    /** Every way the joiner's game differs from the host's, one line each for the player; empty if they match. */
    public static List<String> differences(JSONObject host, JSONObject mine) throws JSONException {
        List<String> diffs = new ArrayList<>();
        if (!Objects.equals(host.optString("version"), mine.optString("version"))) {
            diffs.add("Game version: the host has " + host.optString("version") + ", you have " + mine.optString("version"));
        }
        if (!Objects.equals(host.optString("seed"), mine.optString("seed"))) {
            diffs.add("Save seed: the host's is " + host.optString("seed") + ", yours is " + mine.optString("seed")
                    + " (start a new game with the host's seed, and without devmode, which ignores the seed)");
        }
        JSONObject hostMods = host.getJSONObject("mods");
        JSONObject myMods = mine.getJSONObject("mods");
        Iterator<?> ids = hostMods.keys();
        while (ids.hasNext()) {
            String id = (String) ids.next();
            JSONObject h = hostMods.getJSONObject(id);
            if (!myMods.has(id)) {
                diffs.add("Mod only the host has enabled: " + h.optString("name") + " " + h.optString("version"));
            } else if (!Objects.equals(h.optString("version"), myMods.getJSONObject(id).optString("version"))) {
                diffs.add("Mod version: " + h.optString("name") + " is " + h.optString("version") + " for the host, "
                        + myMods.getJSONObject(id).optString("version") + " for you");
            }
        }
        Iterator<?> mine2 = myMods.keys();
        while (mine2.hasNext()) {
            String id = (String) mine2.next();
            if (!hostMods.has(id)) {
                JSONObject m = myMods.getJSONObject(id);
                diffs.add("Mod only you have enabled: " + m.optString("name") + " " + m.optString("version"));
            }
        }
        return diffs;
    }
}
