package matlabmaster.multiplayer.updates;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.util.Misc;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.HashSet;
import java.util.Set;

/**
 * The salvageable things in the world (derelict ships, caches, probes, research stations...): every game has them
 * (the sector's generation, with the same ids), and salvaging one removes it in the game it was salvaged in. That
 * game tells the server, which removes it from the world and tells everyone; and what the world no longer has goes
 * from a game when its player is there (see client.WorldEntities, server.ServerEntities). Battle debris fields
 * have their own (DebrisSync).
 */
public class EntitySync {

    /** One of the world's salvageable things, still there (not salvaged: those fade out and expire). */
    public static boolean isShared(SectorEntityToken entity) {
        if (entity == null || entity instanceof CampaignFleetAPI || entity instanceof PlanetAPI || entity instanceof CampaignTerrainAPI) return false;
        if (!entity.hasTag(Tags.SALVAGEABLE)) return false;
        if (entity.getMarket() != null && !entity.getMarket().isPlanetConditionMarketOnly()) return false;
        return isPresent(entity);
    }

    /** Still in the game: not expired, not fading out (what salvaging does), still somewhere. */
    public static boolean isPresent(SectorEntityToken entity) {
        return entity != null && !entity.isExpired() && !entity.hasTag(Tags.FADING_OUT_AND_EXPIRING) && entity.getContainingLocation() != null;
    }

    /** The ids of the salvageable things in a location. */
    public static Set<String> idsIn(LocationAPI location) {
        Set<String> ids = new HashSet<>();
        if (location == null) return ids;
        for (SectorEntityToken entity : location.getAllEntities()) {
            if (isShared(entity)) ids.add(entity.getId());
        }
        return ids;
    }

    public static JSONArray toJson(Set<String> ids) {
        return new JSONArray(ids);
    }

    public static Set<String> fromJson(JSONArray array) throws JSONException {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) ids.add(array.getString(i));
        return ids;
    }

    /** Removes one (it fades out, as a salvaged one does); false if this game doesn't have it (any more). */
    public static boolean remove(String id) {
        SectorEntityToken entity = Global.getSector().getEntityById(id);
        if (!isShared(entity)) return false;
        Misc.fadeAndExpire(entity);
        return true;
    }
}
