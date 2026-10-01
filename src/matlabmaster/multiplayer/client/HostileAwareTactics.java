package matlabmaster.multiplayer.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetEncounterContextPlugin;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.ModularFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.TacticalModulePlugin;
import com.fs.starfarer.api.fleet.CrewCompositionAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;

/**
 * Whether a fleet is hostile to the player is its AI's tactical module's call (CampaignFleet.isHostileTo asks the
 * AI, which asks this), and the copies of the world's NPC fleets in a client's game don't think for themselves (the
 * server moves them): they kept showing as neutral after the player had made their faction hostile, even while
 * intercepting them. This wraps a copy's own module: everything is its, except that it's hostile to the player
 * whenever their faction is (this game's reputation, the only authority on it). Never saved: unwrap() takes it off
 * before every save, and it's put back within a second.
 */
public class HostileAwareTactics implements TacticalModulePlugin {
    private final CampaignFleetAPI fleet;
    private final TacticalModulePlugin own;

    private HostileAwareTactics(CampaignFleetAPI fleet, TacticalModulePlugin own) {
        this.fleet = fleet;
        this.own = own;
    }

    /** Wraps the NPC fleets around the player that aren't yet. */
    public static void wrapAround(CampaignFleetAPI player) {
        if (player == null || player.getContainingLocation() == null) return;
        for (CampaignFleetAPI fleet : player.getContainingLocation().getFleets()) {
            if (fleet == player || fleet.hasTag("playerFleet") || !(fleet.getAI() instanceof ModularFleetAIAPI)) continue;
            ModularFleetAIAPI ai = (ModularFleetAIAPI) fleet.getAI();
            TacticalModulePlugin tactics = ai.getTacticalModule();
            if (tactics != null && !(tactics instanceof HostileAwareTactics)) ai.setTacticalModule(new HostileAwareTactics(fleet, tactics));
        }
    }

    /** Puts every fleet's own module back (before saving, and on leaving the server). */
    public static void unwrapAll() {
        for (LocationAPI location : Global.getSector().getAllLocations()) {
            for (CampaignFleetAPI fleet : location.getFleets()) {
                if (!(fleet.getAI() instanceof ModularFleetAIAPI)) continue;
                ModularFleetAIAPI ai = (ModularFleetAIAPI) fleet.getAI();
                if (ai.getTacticalModule() instanceof HostileAwareTactics) ai.setTacticalModule(((HostileAwareTactics) ai.getTacticalModule()).own);
            }
        }
    }

    private boolean hostileToPlayer(CampaignFleetAPI other) {
        return other != null && other == Global.getSector().getPlayerFleet() && fleet.getFaction() != null
                && Global.getSector().getPlayerFaction().isHostileTo(fleet.getFaction());
    }

    @Override public boolean isHostileTo(CampaignFleetAPI other) { return hostileToPlayer(other) || own.isHostileTo(other); }
    @Override public boolean isHostileTo(CampaignFleetAPI other, boolean b) { return hostileToPlayer(other) || own.isHostileTo(other, b); }

    @Override public void advance(float amount) { own.advance(amount); }
    @Override public void setTravelDestination(Vector2f dest, float f) { own.setTravelDestination(dest, f); }
    @Override public void setPriorityTarget(SectorEntityToken target, float f, boolean b) { own.setPriorityTarget(target, f, b); }
    @Override public boolean isFleeing() { return own.isFleeing(); }
    @Override public SectorEntityToken getTarget() { return own.getTarget(); }
    @Override public SectorEntityToken getLargestEnemy() { return own.getLargestEnemy(); }
    @Override public boolean isBusy() { return own.isBusy(); }
    @Override public void performCrashMothballingPriorToEscape(FleetEncounterContextPlugin c, CampaignFleetAPI f) { own.performCrashMothballingPriorToEscape(c, f); }
    @Override public CampaignFleetAIAPI.EncounterOption pickEncounterOption(FleetEncounterContextPlugin c, CampaignFleetAPI f) { return own.pickEncounterOption(c, f); }
    @Override public CampaignFleetAIAPI.EncounterOption pickEncounterOption(FleetEncounterContextPlugin c, CampaignFleetAPI f, boolean b) { return own.pickEncounterOption(c, f, b); }
    @Override public CampaignFleetAIAPI.PursuitOption pickPursuitOption(FleetEncounterContextPlugin c, CampaignFleetAPI f) { return own.pickPursuitOption(c, f); }
    @Override public CampaignFleetAIAPI.BoardingActionDecision makeBoardingDecision(FleetEncounterContextPlugin c, FleetMemberAPI m, CrewCompositionAPI crew) { return own.makeBoardingDecision(c, m, crew); }
    @Override public CampaignFleetAIAPI.InitialBoardingResponse pickBoardingResponse(FleetEncounterContextPlugin c, FleetMemberAPI m, CampaignFleetAPI f) { return own.pickBoardingResponse(c, m, f); }
    @Override public List<FleetMemberAPI> pickBoardingTaskForce(FleetEncounterContextPlugin c, FleetMemberAPI m, CampaignFleetAPI f) { return own.pickBoardingTaskForce(c, m, f); }
    @Override public void reportNearbyAction(CampaignFleetAIAPI.ActionType t, SectorEntityToken a, SectorEntityToken b, String s) { own.reportNearbyAction(t, a, b, s); }
    @Override public void notifyInteractedWith(CampaignFleetAPI f) { own.notifyInteractedWith(f); }
    @Override public void setTarget(SectorEntityToken target) { own.setTarget(target); }
    @Override public void forceTargetReEval() { own.forceTargetReEval(); }
    @Override public boolean wantsToJoin(BattleAPI battle, boolean b) { return own.wantsToJoin(battle, b); }
    @Override public boolean isMaintainingContact() { return own.isMaintainingContact(); }
    @Override public boolean isStandingDown() { return own.isStandingDown(); }
    @Override public float getPursuitDays() { return own.getPursuitDays(); }
    @Override public SectorEntityToken getPriorityTarget() { return own.getPriorityTarget(); }
}
