package matlabmaster.multiplayer.updates;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.intel.PersonBountyIntel;
import matlabmaster.multiplayer.MultiplayerLog;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A player's battles happen in their own game (the world doesn't stop for them), against its copies of the world's
 * NPC fleets. When one is over, their game tells the server how every NPC fleet in it came out, and the server's
 * game (the only authority on NPC fleets) loses the same ships: the ones destroyed, disabled or captured are gone,
 * the rest take the damage, and a fleet with nothing left is destroyed. The player's own fleet is theirs: its
 * losses and captures reach the server like any change to it.
 *
 * Result: {fleetId: {"destroyed": true}} or {fleetId: {"ships": {memberId: {"cr", "hull"}}}} (the survivors).
 */
public class BattleSync {

    /** Client: the NPC fleets of a battle this game's player fought, as they came out of it. */
    public static JSONObject describe(BattleAPI battle) throws JSONException {
        Set<CampaignFleetAPI> fleets = new LinkedHashSet<>();
        //the snapshots have the fleets as the battle started, the sides what's left: a fleet destroyed is only in the first
        addAll(fleets, battle.getSnapshotSideOne());
        addAll(fleets, battle.getSnapshotSideTwo());
        addAll(fleets, battle.getSideOne());
        addAll(fleets, battle.getSideTwo());
        Set<String> ids = new LinkedHashSet<>();
        for (CampaignFleetAPI fleet : fleets) {
            if (fleet.isPlayerFleet() || fleet.hasTag("playerFleet")) continue; //players' fleets are their own games'
            ids.add(fleet.getId());
        }

        JSONObject result = new JSONObject();
        for (String id : ids) {
            SectorEntityToken entity = Global.getSector().getEntityById(id);
            JSONObject outcome = new JSONObject();
            if (!(entity instanceof CampaignFleetAPI) || !((CampaignFleetAPI) entity).isAlive() || ((CampaignFleetAPI) entity).isEmpty()) {
                outcome.put("destroyed", true);
            } else {
                JSONObject ships = new JSONObject();
                for (FleetMemberAPI member : ((CampaignFleetAPI) entity).getFleetData().getMembersListCopy()) {
                    JSONObject ship = new JSONObject();
                    ship.put("cr", member.getRepairTracker().getCR());
                    ship.put("hull", member.getStatus().getHullFraction());
                    ships.put(member.getId(), ship);
                }
                outcome.put("ships", ships);
            }
            result.put(id, outcome);
        }
        return result;
    }

    /**
     * Server: applies a player's battle result to the world's NPC fleets. A bounty's target among them whose wanted
     * ship is gone is the player's (WorldBounties): its reward goes to bountyClaimed.
     */
    public static void apply(String player, JSONObject result, java.util.function.Consumer<JSONObject> bountyClaimed) throws JSONException {
        for (Iterator<?> it = result.keys(); it.hasNext(); ) {
            String id = (String) it.next();
            SectorEntityToken entity = Global.getSector().getEntityById(id);
            if (!(entity instanceof CampaignFleetAPI)) continue; //gone already, or only in their game
            CampaignFleetAPI fleet = (CampaignFleetAPI) entity;
            if (fleet.isPlayerFleet() || fleet.hasTag("playerFleet")) continue;
            //a station isn't destroyed by losing: vanilla disables it and its market deals with that. Despawning it
            //took a market's station out of the world for good (Jangala Station): left to the server's game
            if (fleet.isStationMode()) continue;
            JSONObject outcome = result.getJSONObject(id);
            PersonBountyIntel bounty = WorldBounties.bountyOn(fleet);
            if (bounty != null) {
                String target = WorldBounties.targetShip(bounty, fleet);
                JSONObject ships = outcome.optJSONObject("ships");
                if (outcome.optBoolean("destroyed") || target == null || ships == null || !ships.has(target)) {
                    MultiplayerLog.log().info(player + " beat the bounty target " + fleet.getName());
                    bountyClaimed.accept(WorldBounties.claim(bounty));
                }
            }
            if (outcome.optBoolean("destroyed")) {
                destroy(fleet, player);
                continue;
            }
            JSONObject ships = outcome.getJSONObject("ships");
            int lost = 0;
            for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
                JSONObject ship = ships.optJSONObject(member.getId());
                if (ship == null) { //destroyed, disabled or captured
                    fleet.getFleetData().removeFleetMember(member);
                    lost++;
                    continue;
                }
                member.getRepairTracker().setCR((float) ship.getDouble("cr"));
                member.getStatus().setHullFraction((float) ship.getDouble("hull"));
            }
            if (fleet.getFleetData().getNumMembers() == 0) {
                destroy(fleet, player);
            } else if (lost > 0) {
                MultiplayerLog.log().info(fleet.getName() + " lost " + lost + " ships fighting " + player);
            }
        }
    }

    private static void destroy(CampaignFleetAPI fleet, String player) {
        MultiplayerLog.log().info(fleet.getName() + " was destroyed fighting " + player);
        fleet.despawn(CampaignEventListener.FleetDespawnReason.DESTROYED_BY_BATTLE, null);
    }

    private static void addAll(Set<CampaignFleetAPI> into, List<CampaignFleetAPI> fleets) {
        if (fleets != null) into.addAll(new ArrayList<>(fleets));
    }
}
