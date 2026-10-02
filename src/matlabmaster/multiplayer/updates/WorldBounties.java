package matlabmaster.multiplayer.updates;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActionEnvelope;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActions;
import com.fs.starfarer.api.impl.campaign.intel.PersonBountyIntel;
import com.fs.starfarer.api.impl.campaign.intel.PersonBountyManager;
import com.fs.starfarer.api.util.Misc;
import matlabmaster.multiplayer.MultiplayerLog;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The world's person bounties (its PersonBountyManager posts them, on its own fleets): a player who beats one in
 * their game (BattleSync) gets what vanilla pays the player who does: the bounty, and the faction's goodwill. The
 * world's bounty ends there, as if its own player had claimed it.
 *
 * Reward: {"credits", "faction", "person"}.
 */
public class WorldBounties {

    /** World's side: the bounty on this fleet, if it's a bounty's target (still open). */
    public static PersonBountyIntel bountyOn(CampaignFleetAPI fleet) {
        PersonBountyManager manager = PersonBountyManager.getInstance();
        if (manager == null || fleet == null) return null;
        for (EveryFrameScript script : manager.getActive()) {
            if (!(script instanceof PersonBountyIntel)) continue;
            PersonBountyIntel bounty = (PersonBountyIntel) script;
            if (bounty.getFleet() == fleet && !bounty.isEnding() && !bounty.isEnded()) return bounty;
        }
        return null;
    }

    /** World's side: the id of the ship the wanted person commands in it (what has to go), or null. */
    public static String targetShip(PersonBountyIntel bounty, CampaignFleetAPI fleet) {
        for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
            if (member.getCaptain() != null && member.getCaptain() == bounty.getPerson()) return member.getId();
        }
        return null;
    }

    /** World's side: the bounty claimed by a player: their reward, and the world's bounty ends. */
    public static JSONObject claim(PersonBountyIntel bounty) throws JSONException {
        JSONObject reward = new JSONObject();
        reward.put("credits", (int) bounty.getBountyCredits());
        reward.put("faction", bounty.getFaction().getId());
        reward.put("person", bounty.getPerson() == null ? "the target" : bounty.getPerson().getNameString());
        bounty.endAfterDelay();
        return reward;
    }

    /**
     * World's side: the open bounties, for the players' intel tabs: [{"id", "person", "portrait", "faction",
     * "credits", "location" (system id), "locationName", "daysLeft"}].
     */
    public static JSONArray board() throws JSONException {
        JSONArray board = new JSONArray();
        PersonBountyManager manager = PersonBountyManager.getInstance();
        if (manager == null) return board;
        for (EveryFrameScript script : manager.getActive()) {
            if (!(script instanceof PersonBountyIntel)) continue;
            PersonBountyIntel bounty = (PersonBountyIntel) script;
            if (bounty.isEnding() || bounty.isEnded() || bounty.getPerson() == null || bounty.getFaction() == null) continue;
            JSONObject entry = new JSONObject();
            entry.put("id", bounty.getPerson().getId());
            entry.put("person", bounty.getPerson().getNameString());
            entry.put("portrait", bounty.getPerson().getPortraitSprite());
            entry.put("faction", bounty.getFaction().getId());
            entry.put("credits", (int) bounty.getBountyCredits());
            LocationAPI where = bounty.getHideoutLocation() == null ? null : bounty.getHideoutLocation().getContainingLocation();
            if (where != null) {
                entry.put("location", where.getId());
                entry.put("locationName", where instanceof StarSystemAPI ? ((StarSystemAPI) where).getNameWithLowercaseType() : where.getName());
            }
            entry.put("daysLeft", Math.max(0, Math.round(bounty.getDuration() - bounty.getElapsedDays())));
            board.put(entry);
        }
        return board;
    }

    /** Player's side: the bounty paid, as vanilla's PersonBountyIntel pays its own player. */
    public static void reward(JSONObject reward) throws JSONException {
        FactionAPI faction = Global.getSector().getFaction(reward.getString("faction"));
        int credits = reward.optInt("credits", 0);
        if (faction == null) return;
        if (credits > 0) Global.getSector().getPlayerFleet().getCargo().getCredits().add(credits);
        //vanilla's bounty rep (a medium boost, at any standing) with its message
        Global.getSector().adjustPlayerReputation(new RepActionEnvelope(RepActions.PERSON_BOUNTY_REWARD, null, null, null, true, false), faction.getId());
        if (Global.getSector().getCampaignUI() != null) {
            Global.getSector().getCampaignUI().addMessage("Bounty on " + reward.optString("person") + " collected from " + faction.getDisplayNameWithArticle()
                    + ": " + Misc.getDGSCredits(credits), Misc.getPositiveHighlightColor());
        }
        MultiplayerLog.log().info("Collected the bounty on " + reward.optString("person") + " (" + faction.getId() + "): " + credits + " credits");
    }
}
