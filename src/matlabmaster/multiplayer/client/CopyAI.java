package matlabmaster.multiplayer.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.Script;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetActionTextProvider;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.FleetEncounterContextPlugin;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI;
import com.fs.starfarer.api.fleet.CrewCompositionAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.campaign.ai.ModularFleetAI;
import com.fs.starfarer.campaign.fleet.CampaignFleet;
import matlabmaster.multiplayer.MultiplayerLog;

import java.util.Collections;
import java.util.List;

/**
 * The AI of a copy of one of the world's NPC fleets in a player's game. The copies came with no AI at all, and the
 * game asks a fleet's AI whether it's hostile (the fleet tooltip and map shown neutral at a hostile faction) and what
 * it does in an encounter (with none it never engages, pursues or harries: a hostile patrol let the player leave).
 * This one decides as vanilla's does, from a vanilla ModularFleetAI it holds and never runs, with this game's
 * reputation; but it never moves the fleet (no frame update, no assignments): the server's game does that.
 *
 * Never saved: removeAll() gives copies their vanilla AI back before every save (a normal fleet in the save, and
 * after leaving the server), and installAround() puts this back within a second.
 */
public class CopyAI implements CampaignFleetAIAPI {
    private final CampaignFleetAIAPI vanilla;

    private CopyAI(CampaignFleetAIAPI vanilla) {
        this.vanilla = vanilla;
    }

    /** One of the world's NPC fleets here (not a player's, not a station: those are this game's own). */
    public static void install(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.isPlayerFleet() || fleet.hasTag("playerFleet") || fleet.isStationMode()) return;
        if (fleet.getAI() instanceof CopyAI || !(fleet instanceof CampaignFleet)) return;
        try {
            CampaignFleetAIAPI vanilla = fleet.getAI() != null ? fleet.getAI() : new ModularFleetAI((CampaignFleet) fleet);
            fleet.setAI(new CopyAI(vanilla));
        } catch (Exception e) {
            MultiplayerLog.log().warn("Couldn't give " + fleet.getName() + " a copy AI: " + e.getMessage());
        }
    }

    /** The NPC fleets around the player (every second, while connected). */
    public static void installAround(CampaignFleetAPI player) {
        if (player == null || player.getContainingLocation() == null) return;
        for (CampaignFleetAPI fleet : player.getContainingLocation().getFleets()) install(fleet);
    }

    /** Before saving, and on leaving the server: every copy's vanilla AI back. */
    public static void removeAll() {
        for (LocationAPI location : Global.getSector().getAllLocations()) {
            for (CampaignFleetAPI fleet : location.getFleets()) {
                if (fleet.getAI() instanceof CopyAI) fleet.setAI(((CopyAI) fleet.getAI()).vanilla);
            }
        }
    }

    // --- decisions: vanilla's (with a safe answer if it can't decide without having run) ---

    @Override
    public boolean isHostileTo(CampaignFleetAPI other) {
        try {
            return vanilla.isHostileTo(other);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public EncounterOption pickEncounterOption(FleetEncounterContextPlugin context, CampaignFleetAPI other) {
        try {
            return vanilla.pickEncounterOption(context, other);
        } catch (Exception e) {
            return EncounterOption.HOLD;
        }
    }

    @Override
    public EncounterOption pickEncounterOption(FleetEncounterContextPlugin context, CampaignFleetAPI other, boolean pureCheck) {
        try {
            return vanilla.pickEncounterOption(context, other, pureCheck);
        } catch (Exception e) {
            return EncounterOption.HOLD;
        }
    }

    @Override
    public PursuitOption pickPursuitOption(FleetEncounterContextPlugin context, CampaignFleetAPI other) {
        try {
            return vanilla.pickPursuitOption(context, other);
        } catch (Exception e) {
            return PursuitOption.LET_THEM_GO;
        }
    }

    /** Copies stay out of other battles, as they did with no AI (whether they would is the world's call). */
    @Override public boolean wantsToJoin(BattleAPI battle, boolean playerInvolved) { return false; }

    @Override public InitialBoardingResponse pickBoardingResponse(FleetEncounterContextPlugin c, FleetMemberAPI toBoard, CampaignFleetAPI other) { return vanilla.pickBoardingResponse(c, toBoard, other); }
    @Override public List<FleetMemberAPI> pickBoardingTaskForce(FleetEncounterContextPlugin c, FleetMemberAPI toBoard, CampaignFleetAPI other) { return vanilla.pickBoardingTaskForce(c, toBoard, other); }
    @Override public BoardingActionDecision makeBoardingDecision(FleetEncounterContextPlugin c, FleetMemberAPI toBoard, CrewCompositionAPI max) { return vanilla.makeBoardingDecision(c, toBoard, max); }
    @Override public void performCrashMothballingPriorToEscape(FleetEncounterContextPlugin c, CampaignFleetAPI player) { vanilla.performCrashMothballingPriorToEscape(c, player); }
    @Override public boolean isFleeing() { return vanilla.isFleeing(); }
    @Override public boolean isMaintainingContact() { return vanilla.isMaintainingContact(); }
    @Override public FleetAssignmentDataAPI getCurrentAssignment() { return vanilla.getCurrentAssignment(); }
    @Override public FleetAssignment getCurrentAssignmentType() { return vanilla.getCurrentAssignmentType(); }
    @Override public boolean isCurrentAssignment(FleetAssignment assignment) { return vanilla.isCurrentAssignment(assignment); }
    @Override public List<FleetAssignmentDataAPI> getAssignmentsCopy() { return vanilla.getAssignmentsCopy() == null ? Collections.emptyList() : vanilla.getAssignmentsCopy(); }
    @Override public String getActionTextOverride() { return vanilla.getActionTextOverride(); }
    @Override public void setActionTextOverride(String text) { vanilla.setActionTextOverride(text); }
    @Override public FleetActionTextProvider getActionTextProvider() { return vanilla.getActionTextProvider(); }
    @Override public void setActionTextProvider(FleetActionTextProvider provider) { vanilla.setActionTextProvider(provider); }
    @Override public void notifyInteractedWith(CampaignFleetAPI other) { vanilla.notifyInteractedWith(other); }

    // --- what would make it act on its own: the server's game does that ---

    @Override public void advance(float amount) { }
    @Override public void reportNearbyAction(ActionType type, SectorEntityToken actor, SectorEntityToken target, String responseVariable) { }
    @Override public void doNotAttack(SectorEntityToken other, float days) { }
    @Override public void dumpResourcesIfNeeded() { }
    @Override public void addAssignmentAtStart(FleetAssignment a, SectorEntityToken t, float days, Script done) { }
    @Override public void addAssignmentAtStart(FleetAssignment a, SectorEntityToken t, float days, String text, Script done) { }
    @Override public void addAssignment(FleetAssignment a, SectorEntityToken t, float days, Script done) { }
    @Override public void addAssignment(FleetAssignment a, SectorEntityToken t, float days, String text, Script done) { }
    @Override public void addAssignment(FleetAssignment a, SectorEntityToken t, float days, String text, boolean addTimeToNext, Script start, Script done) { }
    @Override public void removeFirstAssignment() { }
    @Override public void removeFirstAssignmentIfItIs(FleetAssignment assignment) { }
    @Override public void removeAssignment(FleetAssignmentDataAPI assignment) { }
    @Override public void clearAssignments() { }
}
