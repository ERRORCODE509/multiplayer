package matlabmaster.multiplayer.updates;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.utils.FleetSerializer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Markets belong to the world, so the server's game holds their stock. A player opening a market gets the server's
 * stock first (the server restocks it as vanilla does when its own player opens one), and when they leave, what they
 * bought and sold goes back to the server. Only the markets everyone shares: never a player's own storage or
 * colonies, which are theirs alone and stay in their own game.
 *
 * Snapshot: {"marketId", "submarkets": {specId: {"cargo": {key: quantity}, "ships": {memberId: ship}}}}.
 * Trade: {"marketId", "submarkets": {specId: {"cargo": {key: change}, "shipsAdded": {memberId: ship},
 * "shipsRemoved": [memberId]}}}. A cargo key is the item's type and id (see key()).
 */
public class MarketSync {

    /** A submarket every player shares: not a player's own storage, colony stockpile or other free transfer. */
    public static boolean isShared(SubmarketAPI submarket) {
        if (submarket == null || Submarkets.SUBMARKET_STORAGE.equals(submarket.getSpecId())) return false;
        if (submarket.getMarket() == null || submarket.getMarket().isPlayerOwned()) return false;
        return submarket.getPlugin() != null && !submarket.getPlugin().isFreeTransfer();
    }

    public static boolean hasShared(MarketAPI market) {
        if (market == null) return false;
        for (SubmarketAPI submarket : market.getSubmarketsCopy()) {
            if (isShared(submarket)) return true;
        }
        return false;
    }

    /** Server: the market's stock as a player opening it now would find it (restocked as vanilla does on opening). */
    public static JSONObject snapshot(MarketAPI market, boolean restock) throws JSONException {
        JSONObject submarkets = new JSONObject();
        for (SubmarketAPI submarket : market.getSubmarketsCopy()) {
            if (!isShared(submarket)) continue;
            if (restock) {
                try {
                    submarket.getPlugin().updateCargoPrePlayerInteraction();
                } catch (Exception e) {
                    MultiplayerLog.log().warn("Couldn't restock " + market.getId() + "/" + submarket.getSpecId() + ": " + e.getMessage());
                }
            }
            submarkets.put(submarket.getSpecId(), describe(submarket.getCargo()));
        }
        JSONObject snapshot = new JSONObject();
        snapshot.put("marketId", market.getId());
        snapshot.put("submarkets", submarkets);
        return snapshot;
    }

    /**
     * Client: replaces the market's stock with the server's. Its own restock runs first, so its timers start over
     * and opening the trade screen right after doesn't restock it again on top of the server's.
     */
    public static void apply(MarketAPI market, JSONObject snapshot) throws JSONException {
        JSONObject submarkets = snapshot.getJSONObject("submarkets");
        for (Iterator<?> it = submarkets.keys(); it.hasNext(); ) {
            String specId = (String) it.next();
            SubmarketAPI submarket = market.getSubmarket(specId);
            if (!isShared(submarket)) continue;
            try {
                submarket.getPlugin().updateCargoPrePlayerInteraction();
            } catch (Exception e) {
                MultiplayerLog.log().warn("Couldn't reset the restock timers of " + market.getId() + "/" + specId + ": " + e.getMessage());
            }
            CargoAPI cargo = submarket.getCargo();
            cargo.clear();
            if (cargo.getMothballedShips() == null) cargo.initMothballedShips(market.getFactionId());
            for (FleetMemberAPI member : cargo.getMothballedShips().getMembersListCopy()) {
                cargo.getMothballedShips().removeFleetMember(member);
            }
            JSONObject wanted = submarkets.getJSONObject(specId);
            JSONObject items = wanted.getJSONObject("cargo");
            for (Iterator<?> keys = items.keys(); keys.hasNext(); ) {
                String key = (String) keys.next();
                add(cargo, key, (float) items.getDouble(key));
            }
            JSONObject ships = wanted.getJSONObject("ships");
            for (Iterator<?> ids = ships.keys(); ids.hasNext(); ) {
                String memberId = (String) ids.next();
                addShip(cargo, memberId, ships.getJSONObject(memberId));
            }
        }
    }

    /** Client: what the player bought and sold since before (a snapshot of this market, as applied), or null if nothing. */
    public static JSONObject trade(MarketAPI market, JSONObject before) throws JSONException {
        JSONObject now = snapshot(market, false).getJSONObject("submarkets");
        JSONObject then = before.getJSONObject("submarkets");
        JSONObject submarkets = new JSONObject();
        for (Iterator<?> it = then.keys(); it.hasNext(); ) {
            String specId = (String) it.next();
            if (!now.has(specId)) continue;
            JSONObject a = then.getJSONObject(specId), b = now.getJSONObject(specId);

            JSONObject cargo = new JSONObject();
            JSONObject itemsBefore = a.getJSONObject("cargo"), itemsNow = b.getJSONObject("cargo");
            for (Iterator<?> keys = itemsNow.keys(); keys.hasNext(); ) {
                String key = (String) keys.next();
                double change = itemsNow.getDouble(key) - itemsBefore.optDouble(key, 0);
                if (Math.abs(change) >= 0.001) cargo.put(key, change);
            }
            for (Iterator<?> keys = itemsBefore.keys(); keys.hasNext(); ) {
                String key = (String) keys.next();
                if (!itemsNow.has(key)) cargo.put(key, -itemsBefore.getDouble(key));
            }

            JSONObject shipsBefore = a.getJSONObject("ships"), shipsNow = b.getJSONObject("ships");
            JSONObject added = new JSONObject();
            JSONArray removed = new JSONArray();
            for (Iterator<?> ids = shipsNow.keys(); ids.hasNext(); ) {
                String id = (String) ids.next();
                if (!shipsBefore.has(id)) added.put(id, shipsNow.getJSONObject(id)); //sold here
            }
            for (Iterator<?> ids = shipsBefore.keys(); ids.hasNext(); ) {
                String id = (String) ids.next();
                if (!shipsNow.has(id)) removed.put(id); //bought
            }

            if (cargo.length() == 0 && added.length() == 0 && removed.length() == 0) continue;
            JSONObject change = new JSONObject();
            change.put("cargo", cargo);
            change.put("shipsAdded", added);
            change.put("shipsRemoved", removed);
            submarkets.put(specId, change);
        }
        if (submarkets.length() == 0) return null;
        JSONObject trade = new JSONObject();
        trade.put("marketId", market.getId());
        trade.put("submarkets", submarkets);
        return trade;
    }

    /** Server: applies a player's trade(); what's gone already (another player bought it first) is skipped. */
    public static void applyTrade(MarketAPI market, JSONObject trade) throws JSONException {
        JSONObject submarkets = trade.getJSONObject("submarkets");
        for (Iterator<?> it = submarkets.keys(); it.hasNext(); ) {
            String specId = (String) it.next();
            SubmarketAPI submarket = market.getSubmarket(specId);
            if (!isShared(submarket)) continue;
            CargoAPI cargo = submarket.getCargo();
            JSONObject change = submarkets.getJSONObject(specId);
            JSONObject items = change.getJSONObject("cargo");
            for (Iterator<?> keys = items.keys(); keys.hasNext(); ) {
                String key = (String) keys.next();
                add(cargo, key, (float) items.getDouble(key));
            }
            JSONArray removed = change.getJSONArray("shipsRemoved");
            for (int i = 0; i < removed.length(); i++) {
                String id = removed.getString(i);
                for (FleetMemberAPI member : cargo.getMothballedShips().getMembersListCopy()) {
                    if (member.getId().equals(id)) cargo.getMothballedShips().removeFleetMember(member);
                }
            }
            JSONObject added = change.getJSONObject("shipsAdded");
            for (Iterator<?> ids = added.keys(); ids.hasNext(); ) {
                String id = (String) ids.next();
                addShip(cargo, id, added.getJSONObject(id));
            }
        }
    }

    private static JSONObject describe(CargoAPI cargo) throws JSONException {
        JSONObject items = new JSONObject();
        for (CargoStackAPI stack : cargo.getStacksCopy()) {
            String key = key(stack);
            if (key == null) continue;
            items.put(key, items.optDouble(key, 0) + stack.getSize());
        }
        JSONObject described = new JSONObject();
        described.put("cargo", items);
        described.put("ships", cargo.getMothballedShips() == null ? new JSONObject() : FleetSerializer.serializeFleetShips(cargo.getMothballedShips()));
        return described;
    }

    /** "R:food", "W:lightmg", "F:broadsword_wing", "S:id" or "S:id|data", or null for anything else. */
    private static String key(CargoStackAPI stack) {
        Object data = stack.getData();
        switch (stack.getType()) {
            case RESOURCES: return "R:" + data;
            case WEAPONS: return "W:" + data;
            case FIGHTER_CHIP: return "F:" + data;
            case SPECIAL:
                SpecialItemData special = stack.getSpecialDataIfSpecial();
                if (special == null) return null;
                return "S:" + special.getId() + (special.getData() == null ? "" : "|" + special.getData());
            default: return null;
        }
    }

    /** Adds (or, negative, removes up to what's there) the items of a key(). */
    private static void add(CargoAPI cargo, String key, float quantity) {
        if (key.length() < 3 || quantity == 0) return;
        String id = key.substring(2);
        CargoAPI.CargoItemType type;
        Object data;
        switch (key.charAt(0)) {
            case 'R': type = CargoAPI.CargoItemType.RESOURCES; data = id; break;
            case 'W': type = CargoAPI.CargoItemType.WEAPONS; data = id; break;
            case 'F': type = CargoAPI.CargoItemType.FIGHTER_CHIP; data = id; break;
            case 'S': {
                int bar = id.indexOf('|');
                type = CargoAPI.CargoItemType.SPECIAL;
                data = bar < 0 ? new SpecialItemData(id, null) : new SpecialItemData(id.substring(0, bar), id.substring(bar + 1));
                break;
            }
            default: return;
        }
        if (quantity > 0) {
            cargo.addItems(type, data, quantity);
        } else {
            float there = cargo.getQuantity(type, data);
            if (there > 0) cargo.removeItems(type, data, Math.min(there, -quantity));
        }
    }

    private static void addShip(CargoAPI cargo, String memberId, JSONObject ship) throws JSONException {
        if (cargo.getMothballedShips() == null) cargo.initMothballedShips("neutral");
        for (FleetMemberAPI member : cargo.getMothballedShips().getMembersListCopy()) {
            if (member.getId().equals(memberId)) return; //there already
        }
        FleetMemberAPI member = FleetSerializer.unSerializeFleetMember(ship);
        member.setId(memberId);
        cargo.getMothballedShips().addFleetMember(member);
    }
}
