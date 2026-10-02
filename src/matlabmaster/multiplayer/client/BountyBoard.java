package matlabmaster.multiplayer.client;

import com.fs.starfarer.api.Global;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The world's open person bounties in this game's intel tab, while connected (see WorldBountyIntel). Kept out of
 * saves: taken out before every save and on leaving, put back within a second from the last list.
 */
public class BountyBoard {
    private final Map<String, WorldBountyIntel> shown = new HashMap<>();
    /** The world's last list (null: none yet this session). */
    private JSONArray last;

    /** The world's list (on joining, then when it changes): new ones posted, gone ones taken down. */
    public void listed(JSONArray bounties) {
        boolean firstList = last == null;
        last = bounties;
        show(bounties, firstList);
    }

    private void show(JSONArray bounties, boolean quiet) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < bounties.length(); i++) {
            JSONObject bounty = bounties.optJSONObject(i);
            if (bounty == null) continue;
            String id = bounty.optString("id");
            ids.add(id);
            WorldBountyIntel intel = shown.get(id);
            if (intel != null) {
                intel.update(bounty);
                continue;
            }
            intel = new WorldBountyIntel(bounty);
            shown.put(id, intel);
            Global.getSector().getIntelManager().addIntel(intel, quiet); //a new one is announced, as vanilla's are
        }
        for (String id : new HashSet<>(shown.keySet())) {
            if (!ids.contains(id)) Global.getSector().getIntelManager().removeIntel(shown.remove(id));
        }
    }

    /** The world's last list, for paying its system bounties after a battle (WorldBounties.paySystemBounties). */
    public JSONArray list() {
        return last;
    }

    /** Every second while connected: back after a save took them out. */
    public void keep() {
        if (last != null && shown.isEmpty() && last.length() > 0) show(last, true);
    }

    /** Before a save, and on leaving: out of the intel tab (the next keep() puts them back while connected). */
    public void removeAll() {
        for (WorldBountyIntel intel : shown.values()) Global.getSector().getIntelManager().removeIntel(intel);
        shown.clear();
    }

    /** Left the server or loaded another game: the list is the next server's to give. */
    public void reset() {
        removeAll();
        last = null;
    }

    /** Another game was loaded: the old game's intel went with it. */
    public void forget() {
        shown.clear();
        last = null;
    }
}
