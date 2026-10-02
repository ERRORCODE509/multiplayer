package matlabmaster.multiplayer.updates;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.intel.events.HostileActivityEventIntel;
import com.fs.starfarer.api.impl.campaign.intel.group.FGBlockadeAction;
import com.fs.starfarer.api.impl.campaign.intel.group.KnightsOfLuddTakeoverExpedition;
import com.fs.starfarer.api.impl.campaign.intel.group.PerseanLeagueBlockade;

/**
 * The world's versions of vanilla's two crisis blockades, for a player's colony (see RaidSync). Vanilla's call
 * themselves off when the game's own player has no colony crisis, which a dedicated server's never has (it has no
 * colonies): these only check what decides how they go, that their key fleets are still there. With the host's own
 * crisis running they do as vanilla's.
 */
public class WorldBlockades {

    /** How many of the group's fleets have a flag (the armada, the supply fleets). */
    private static int count(Iterable<CampaignFleetAPI> fleets, String flag) {
        int count = 0;
        for (CampaignFleetAPI fleet : fleets) {
            if (fleet.getMemoryWithoutUpdate().getBoolean(flag)) count++;
        }
        return count;
    }

    /** The Persean League's: over once its armada or both supply fleets are gone. */
    public static class LeagueBlockade extends PerseanLeagueBlockade {
        public LeagueBlockade(GenericRaidParams params, FGBlockadeAction.FGBlockadeParams blockadeParams) {
            super(params, blockadeParams);
        }

        @Override
        protected void periodicUpdate() {
            if (HostileActivityEventIntel.get() != null) {
                super.periodicUpdate();
                return;
            }
            if (!isSpawnedFleets() || isSpawning()) return;
            if (count(getFleets(), ARMADA) <= 0 || count(getFleets(), SUPPLY) <= 0) abort();
        }
    }

    /**
     * The Knights of Ludd's takeover: over once its armada is gone, or the colony no longer has its Luddic majority.
     * The monthly unrest and the takeover itself happen in the owner's game (its own expedition, on the real colony,
     * as the world says it's blockading): never to the world's copy, whose stability is the owner's.
     */
    public static class TakeoverExpedition extends KnightsOfLuddTakeoverExpedition {
        public TakeoverExpedition(GenericRaidParams params, FGBlockadeAction.FGBlockadeParams blockadeParams) {
            super(params, blockadeParams);
            Global.getSector().getListenerManager().removeListener(this);
        }

        @Override
        public void reportEconomyTick(int iterIndex) {
        }

        @Override
        protected void periodicUpdate() {
            if (HostileActivityEventIntel.get() != null) {
                super.periodicUpdate();
                return;
            }
            if (isEnded() || isEnding() || isSucceeded() || isFailed() || isAborted()) return;
            if (getBlockadeParams().specificMarket != null && !getBlockadeParams().specificMarket.hasCondition(Conditions.LUDDIC_MAJORITY)) {
                finish(false);
                return;
            }
            if (!isSpawnedFleets() || isSpawning()) return;
            if (count(getFleets(), ARMADA) <= 0) abort();
        }
    }
}
