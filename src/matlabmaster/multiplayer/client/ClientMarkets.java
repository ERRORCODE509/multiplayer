package matlabmaster.multiplayer.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.updates.MarketSync;
import matlabmaster.multiplayer.utils.ColonyMirrors;
import org.json.JSONObject;

/**
 * A client's side of MarketSync, on the game thread: asks the server for a market's stock when the player opens it,
 * shows that stock instead of this game's own, and sends what they bought and sold when they leave.
 */
public class ClientMarkets {
    /** The market the player is at, while a dialog with it is open. */
    private MarketAPI open;
    /** Its stock as the server's snapshot made it here: what the trade is measured against. */
    private JSONObject before;
    /** Our own colony, while we're in a dialog there. */
    private MarketAPI ownOpen;

    /*
     * Our own colonies trade like normal colonies: visitors trade at their open market (see MarketSync). This game
     * holds the real stock, so the server asks it for the stock when someone visits, and gives it their trades.
     */

    /** The server wants one of our colonies' stock (a visitor opened it): restocked as vanilla does on opening. */
    public void colonyStockRequest(Client client, String marketId) {
        MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
        if (market == null || !market.isPlayerOwned()) return;
        sendColonyStock(client, market, true);
    }

    /** A visitor's trade at one of our colonies (now, or while we were away): into its real stock. */
    public void colonyTrade(JSONObject trade) {
        try {
            MarketAPI market = Global.getSector().getEconomy().getMarket(trade.getString("marketId"));
            if (market == null || !market.isPlayerOwned()) return; //not ours any more
            float value = MarketSync.tradeValue(market, trade);
            MarketSync.applyTrade(market, trade, true);
            float tariff = MarketSync.payTariff(market, value); //the colony's tariff on it is ours
            MultiplayerLog.log().info("A visitor traded at " + market.getName() + ": " + (int) tariff + " credits in tariffs, paid at month's end");
        } catch (Exception e) {
            MultiplayerLog.log().error("Couldn't apply a visitor's trade at our colony", e);
        }
    }

    /** All our colonies' stock, for visitors while we're away (on joining, restocked as if we'd just opened them). */
    public void sendAllColonyStock(Client client) {
        if (client.isSelfHosted) return; //the host's colonies are real in the server's game
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (market.isPlayerOwned() && MarketSync.hasTradable(market)) sendColonyStock(client, market, true);
        }
    }

    private void sendColonyStock(Client client, MarketAPI market, boolean restock) {
        try {
            JSONObject packet = new JSONObject();
            packet.put("commandId", "colonyStock");
            packet.put("snapshot", MarketSync.snapshot(market, restock, true));
            client.send(packet.toString());
        } catch (Exception e) {
            MultiplayerLog.log().error("Couldn't send the stock of our colony " + market.getName(), e);
        }
    }

    /** A dialog opened (PauseUtility): if it's at a market everyone shares, ask the server for its stock. */
    public void dialogOpened(Client client, SectorEntityToken target) {
        open = null;
        before = null;
        ownOpen = null;
        if (target == null) return;
        MarketAPI market = target.getMarket();
        if (client.isSelfHosted) {
            //the host's game is the world: its markets are the real ones, except another player's colony, whose owner's
            //game holds the real stock: the host trades there as any visitor (its stock from the owner, its trades to
            //them), only the world's copy already has them (see ServerMarkets.trade)
            if (!ColonyMirrors.isMirror(market) || !MarketSync.hasShared(market)) return;
            open = market;
            try {
                before = MarketSync.snapshot(market, false);
            } catch (Exception e) {
                before = null;
            }
            request(client, market);
            return;
        }
        if (market != null && market.isPlayerOwned() && MarketSync.hasTradable(market)) {
            ownOpen = market; //our own colony: its stock is ours, the server gets it when we leave
            return;
        }
        if (!MarketSync.hasShared(market)) return;
        open = market;
        request(client, market);
    }

    private void request(Client client, MarketAPI market) {
        try {
            JSONObject packet = new JSONObject();
            packet.put("commandId", "requestMarket");
            packet.put("marketId", market.getId());
            client.send(packet.toString());
        } catch (Exception e) {
            MultiplayerLog.log().error("Couldn't ask for the stock of " + market.getName(), e);
        }
    }

    /** The server's stock arrived: shown here, if the player is still at that market. */
    public void snapshot(JSONObject snapshot) {
        if (open == null || !open.getId().equals(snapshot.optString("marketId"))) return;
        try {
            MarketSync.apply(open, snapshot);
            before = MarketSync.snapshot(open, false);
        } catch (Exception e) {
            before = null; //this market's trades stay in this game
            MultiplayerLog.log().error("Couldn't show the server's stock of " + open.getName(), e);
        }
    }

    /** The dialog closed (PauseUtility): what the player bought and sold goes to the server's market. */
    public void dialogClosed(Client client) {
        if (ownOpen != null) {
            sendColonyStock(client, ownOpen, false); //as we left it: what visitors find while we're away
            ownOpen = null;
        }
        MarketAPI market = open;
        JSONObject from = before;
        open = null;
        before = null;
        if (market == null) return;
        if (from == null) {
            MultiplayerLog.log().warn("Left " + market.getName() + " before the server's stock arrived: trades there stay in this game");
            return;
        }
        try {
            JSONObject trade = MarketSync.trade(market, from);
            if (trade == null) return;
            JSONObject packet = new JSONObject();
            packet.put("commandId", "marketTrade");
            packet.put("trade", trade);
            client.send(packet.toString());
            MultiplayerLog.log().info("Sent the trades at " + market.getName() + " to the server");
        } catch (Exception e) {
            MultiplayerLog.log().error("Couldn't send the trades at " + market.getName(), e);
        }
    }
}
