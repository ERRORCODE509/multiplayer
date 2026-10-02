package matlabmaster.multiplayer.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.json.JSONObject;

import java.awt.Color;
import java.util.Set;

/**
 * One of the world's person bounties, in a player's intel tab (BountyBoard): the world posts them, so this game's
 * own bounty manager knows nothing of them. Beating the target pays as vanilla does (WorldBounties). Never saved:
 * BountyBoard takes them out before every save and puts them back after.
 */
public class WorldBountyIntel extends BaseIntelPlugin {
    private final String person;
    private final String portrait;
    private final String factionId;
    private final int credits;
    private final String locationId;
    private final String locationName;
    private float daysLeft;

    WorldBountyIntel(JSONObject bounty) {
        person = bounty.optString("person", "Unknown");
        portrait = bounty.optString("portrait", null);
        factionId = bounty.optString("faction");
        credits = bounty.optInt("credits");
        locationId = bounty.optString("location", null);
        locationName = bounty.optString("locationName", "parts unknown");
        daysLeft = (float) bounty.optDouble("daysLeft", 0);
    }

    void update(JSONObject bounty) {
        daysLeft = (float) bounty.optDouble("daysLeft", daysLeft);
    }

    private FactionAPI faction() {
        FactionAPI faction = Global.getSector().getFaction(factionId);
        return faction != null ? faction : Global.getSector().getFaction("independent");
    }

    @Override
    protected String getName() {
        return "Bounty - " + person;
    }

    @Override
    protected void addBulletPoints(TooltipMakerAPI info, ListInfoMode mode, boolean isUpdate, Color tc, float initPad) {
        Color h = Misc.getHighlightColor();
        info.addPara("Posted by " + faction().getDisplayNameWithArticle(), initPad, tc, faction().getBaseUIColor(), faction().getDisplayNameWithArticle());
        info.addPara("%s reward", 0f, tc, h, Misc.getDGSCredits(credits));
        info.addPara("%s days left", 0f, tc, h, String.valueOf(Math.max(0, Math.round(daysLeft))));
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        float opad = 10f;
        if (portrait != null) info.addImage(portrait, 128f, 0f);
        info.addPara(faction().getDisplayNameWithArticle() + " has posted a bounty on " + person + ", last seen in or near "
                + locationName + ".", opad, faction().getBaseUIColor(), faction().getDisplayNameWithArticle());
        info.addPara("A bounty of %s is offered for destroying the ship " + person + " commands.", opad,
                Misc.getHighlightColor(), Misc.getDGSCredits(credits));
        info.addPara("The bounty is posted in the multiplayer world: whoever beats the target first collects it.", opad,
                Misc.getGrayColor(), "whoever beats the target first");
        addBulletPoints(info, ListInfoMode.IN_DESC);
    }

    @Override
    public String getIcon() {
        return portrait != null ? portrait : faction().getCrest();
    }

    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add(Tags.INTEL_BOUNTY);
        tags.add(factionId);
        return tags;
    }

    @Override
    public FactionAPI getFactionForUIColors() {
        return faction();
    }

    @Override
    public SectorEntityToken getMapLocation(SectorMapAPI map) {
        if (locationId == null) return null;
        for (LocationAPI location : Global.getSector().getAllLocations()) {
            if (!locationId.equals(location.getId())) continue;
            if (location instanceof StarSystemAPI) return ((StarSystemAPI) location).getHyperspaceAnchor();
        }
        return null;
    }
}
