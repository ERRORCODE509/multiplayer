package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.campaign.BaseCampaignPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;

import java.util.Map;

/**
 * Players don't fight each other. Another player's fleet is a copy in this game (tagged playerFleet), moved by their
 * game, which alone could fight it out; so meeting one opens this dialog instead of vanilla's fleet encounter: their
 * fleet, and leaving, with nothing to engage, pursue or harry. And it's never pulled into anyone's battle (see
 * keepOutOfBattles). In every game: a player's, and the server's, where the host can meet the clients' fleets.
 */
public class PlayerEncounters extends BaseCampaignPlugin {
    private static final String ID = "multiplayer_player_encounters";
    private static final String LEAVE = "mp_leave";

    /** On every game load: picked over vanilla's (and other mods') fleet encounter for players' fleets. Not saved. */
    public static void register() {
        Global.getSector().unregisterPlugin(ID);
        Global.getSector().registerPlugin(new PlayerEncounters());
    }

    /** Whether a fleet is another player's (a copy of it in this game). */
    public static boolean isOtherPlayer(SectorEntityToken entity) {
        return entity instanceof CampaignFleetAPI && entity.hasTag("playerFleet") && !((CampaignFleetAPI) entity).isPlayerFleet();
    }

    /**
     * A player's fleet copy never joins a battle near it (vanilla pulls in fleets around an encounter that want to
     * join, and a copy's AI could): it'd be fought, or fight, here, without its player.
     */
    public static void keepOutOfBattles(CampaignFleetAPI copy) {
        copy.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public PluginPick<InteractionDialogPlugin> pickInteractionDialogPlugin(SectorEntityToken target) {
        if (!isOtherPlayer(target)) return null;
        return new PluginPick<>(new Dialog(), CampaignPlugin.PickPriority.HIGHEST);
    }

    @Override
    public PluginPick<InteractionDialogPlugin> pickInteractionDialogPlugin(Object param, SectorEntityToken target) {
        return pickInteractionDialogPlugin(target);
    }

    private static class Dialog implements InteractionDialogPlugin {
        private InteractionDialogAPI dialog;

        @Override
        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            CampaignFleetAPI other = (CampaignFleetAPI) dialog.getInteractionTarget();
            dialog.getVisualPanel().setVisualFade(0.25f, 0.25f);
            dialog.getVisualPanel().showFleetInfo("Your fleet", Global.getSector().getPlayerFleet(), other.getName(), other);
            dialog.getTextPanel().addPara("Your " + (Global.getSector().getPlayerFleet().getFleetData().getNumMembers() > 1 ? "fleet" : "ship")
                    + " comes alongside " + other.getName() + ", another player's. Players don't fight each other: you pass each other by.");
            dialog.getOptionPanel().addOption("Leave", LEAVE);
            dialog.setOptionOnEscape("Leave", LEAVE);
        }

        @Override
        public void optionSelected(String text, Object data) {
            if (LEAVE.equals(data)) dialog.dismiss();
        }

        @Override public void optionMousedOver(String text, Object data) { }
        @Override public void advance(float amount) { }
        @Override public void backFromEngagement(EngagementResultAPI result) { }
        @Override public Object getContext() { return null; }
        @Override public Map<String, MemoryAPI> getMemoryMap() { return null; }
    }
}
