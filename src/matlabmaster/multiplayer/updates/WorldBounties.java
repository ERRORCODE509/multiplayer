package matlabmaster.multiplayer.updates;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActionEnvelope;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActions;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.PersonBountyIntel;
import com.fs.starfarer.api.impl.campaign.intel.PersonBountyManager;
import com.fs.starfarer.api.impl.campaign.intel.SystemBountyIntel;
import com.fs.starfarer.api.impl.campaign.intel.SystemBountyManager;
import com.fs.starfarer.api.util.Misc;
import matlabmaster.multiplayer.MultiplayerLog;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The world's bounties. Person bounties (its PersonBountyManager posts them, on its own fleets): a player who beats
 * one in their game (BattleSync) gets what vanilla pays the player who does: the bounty, and the faction's goodwill;
 * the world's bounty ends there, as if its own player had claimed it. System bounties (SystemBountyManager): the
 * players are told where they are, and their own battles nearby pay as vanilla's do (paySystemBounties).
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
        SystemBountyManager systems = SystemBountyManager.getInstance();
        if (systems == null) return board;
        for (EveryFrameScript script : systems.getActive()) {
            if (!(script instanceof SystemBountyIntel)) continue;
            SystemBountyIntel bounty = (SystemBountyIntel) script;
            MarketAPI market = bounty.getMarket();
            if (bounty.isEnding() || bounty.isEnded() || market == null || market.getPrimaryEntity() == null) continue;
            JSONObject entry = new JSONObject();
            entry.put("id", "system:" + market.getId());
            entry.put("kind", "system");
            entry.put("market", market.getId());
            entry.put("marketName", market.getName());
            entry.put("faction", market.getFactionId());
            entry.put("baseBounty", (int) bounty.getBaseBounty());
            entry.put("commerce", bounty.isCommerceMode());
            LocationAPI where = market.getContainingLocation();
            if (where != null) {
                entry.put("location", where.getId());
                entry.put("locationName", where instanceof StarSystemAPI ? ((StarSystemAPI) where).getNameWithLowercaseType() : where.getName());
            }
            if (!bounty.isCommerceMode()) entry.put("daysLeft", Math.max(0, Math.round(bounty.getDuration() - bounty.getElapsedDays())));
            board.put(entry);
        }
        return board;
    }

    /**
     * Player's side, after a battle of ours: the world's system bounties near it pay as vanilla's SystemBountyIntel
     * does (per ship lost on the other side, by hull size, for our share of the fighting), but not where this game
     * has its own bounty on the same market (its own still pays: a save's bounties keep listening while connected).
     */
    public static void paySystemBounties(org.json.JSONArray board, CampaignFleetAPI primaryWinner, BattleAPI battle) throws JSONException {
        if (board == null || battle == null || !battle.isPlayerInvolved() || primaryWinner == null) return;
        SystemBountyManager local = SystemBountyManager.getInstance();
        for (int i = 0; i < board.length(); i++) {
            JSONObject entry = board.getJSONObject(i);
            if (!"system".equals(entry.optString("kind"))) continue;
            MarketAPI market = Global.getSector().getEconomy().getMarket(entry.getString("market"));
            if (market == null || !Misc.isNear(primaryWinner, market.getLocationInHyperspace())) continue;
            if (local != null) {
                SystemBountyIntel own = local.getActive(market);
                if (own != null && !own.isEnding() && !own.isEnded()) continue;
            }
            boolean commerce = entry.optBoolean("commerce");
            float baseBounty = entry.optInt("baseBounty");
            int payment = 0;
            float fpDestroyed = 0;
            for (CampaignFleetAPI other : battle.getNonPlayerSideSnapshot()) {
                if (commerce) {
                    if (!market.getFaction().isHostileTo(other.getFaction()) && !other.getFaction().isHostileTo(Factions.INDEPENDENT)) continue;
                    if (Misc.isTrader(other)) continue;
                    if (Factions.INDEPENDENT.equals(other.getFaction().getId())) continue;
                } else if (!market.getFaction().isHostileTo(other.getFaction())) {
                    continue;
                }
                float bounty = 0;
                for (FleetMemberAPI loss : Misc.getSnapshotMembersLost(other)) {
                    bounty += Misc.getSizeNum(loss.getHullSpec().getHullSize()) * baseBounty;
                    fpDestroyed += loss.getFleetPointCost();
                }
                payment += (int) (bounty * battle.getPlayerInvolvementFraction());
            }
            if (payment <= 0) continue;
            Global.getSector().getPlayerFleet().getCargo().getCredits().add(payment);
            float repFP = (int) (fpDestroyed * battle.getPlayerInvolvementFraction());
            Global.getSector().adjustPlayerReputation(new RepActionEnvelope(RepActions.SYSTEM_BOUNTY_REWARD, Float.valueOf(repFP), null, null, true, false), market.getFactionId());
            if (Global.getSector().getCampaignUI() != null) {
                Global.getSector().getCampaignUI().addMessage("System bounty at " + market.getName() + ": " + Misc.getDGSCredits(payment) + " received",
                        Misc.getPositiveHighlightColor());
            }
            MultiplayerLog.log().info("System bounty at " + market.getName() + ": " + payment + " credits");
        }
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
