package matlabmaster.multiplayer.client;

import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.updates.MarketSync;
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

    /** A dialog opened (PauseUtility): if it's at a market everyone shares, ask the server for its stock. */
    public void dialogOpened(Client client, SectorEntityToken target) {
        open = null;
        before = null;
        if (client.isSelfHosted || target == null) return; //the host's game is the world: its markets are the real ones
        MarketAPI market = target.getMarket();
        if (!MarketSync.hasShared(market)) return;
        open = market;
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
