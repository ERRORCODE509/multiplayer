package matlabmaster.multiplayer.server;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.updates.MarketSync;
import matlabmaster.multiplayer.utils.ColonyMirrors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The server's side of MarketSync, on the game thread: gives players a market's stock and takes their trades.
 *
 * An NPC market is this game's: restocked here as vanilla does on opening, and traded at here. So is the host's own
 * colony ("host current game"). Another player's colony trades like a normal colony, at its mirror here: its owner's
 * game holds the real stock, so while they're online it's asked for it (restocked there) and given the trades;
 * while they're offline, visitors get the stock it last sent, and the trades wait for the owner's next session.
 */
public class ServerMarkets {
    /** How long a visitor waits for an online owner's stock before getting the last one it sent (seconds). */
    private static final float OWNER_TIMEOUT = 2f;

    private final Server server;
    /** Visitors waiting for a colony's stock from its owner: market id -> client ids, and since when. */
    private final Map<String, Set<String>> waiting = new HashMap<>();
    private final Map<String, Float> waitingSince = new HashMap<>();
    private float now = 0f;

    ServerMarkets(Server server) {
        this.server = server;
    }

    /** A player opened a market: its stock. */
    void request(String clientId, String marketId) {
        try {
            MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
            if (market != null) {
                boolean hostColony = market.isPlayerOwned(); //this game's own: the host's colony
                if (hostColony ? !MarketSync.hasTradable(market) : !MarketSync.hasShared(market)) return;
                send(clientId, MarketSync.snapshot(market, true, hostColony));
                return;
            }
            MarketAPI mirror = ColonyMirrors.find(marketId);
            if (mirror == null || !MarketSync.hasShared(mirror)) return;
            String owner = server.clientOf(ColonyMirrors.ownerOf(mirror));
            if (owner == null) { //offline: the stock their game last sent
                send(clientId, MarketSync.snapshot(mirror, false));
                return;
            }
            waiting.computeIfAbsent(marketId, id -> new HashSet<>()).add(clientId);
            waitingSince.putIfAbsent(marketId, now);
            JSONObject ask = new JSONObject();
            ask.put("commandId", "colonyStockRequest");
            ask.put("marketId", marketId);
            server.sendTo(owner, ask.toString());
        } catch (Exception e) {
            MultiplayerLog.log().error("Failed to send market " + marketId + " to " + clientId, e);
        }
    }

    /** A player's colony's stock, from their game (asked for, or after they traded there themselves). */
    void stock(String clientId, JSONObject snapshot) {
        try {
            String marketId = snapshot.getString("marketId");
            MarketAPI mirror = ColonyMirrors.find(marketId);
            String player = server.clientPlayers.get(clientId);
            if (mirror == null || player == null || !player.equals(ColonyMirrors.ownerOf(mirror))) return; //not theirs
            MarketSync.apply(mirror, snapshot, false);
            answer(marketId);
        } catch (Exception e) {
            MultiplayerLog.log().error("Failed to take " + clientId + "'s colony stock", e);
        }
    }

    /** A player's trade at a market. */
    void trade(String clientId, JSONObject trade) {
        try {
            String marketId = trade.getString("marketId");
            MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
            if (market != null) {
                MarketSync.applyTrade(market, trade, market.isPlayerOwned());
                MultiplayerLog.log().info(clientId + " traded at " + market.getName());
                return;
            }
            MarketAPI mirror = ColonyMirrors.find(marketId);
            if (mirror == null) return;
            MarketSync.applyTrade(mirror, trade);
            String ownerId = ColonyMirrors.ownerOf(mirror);
            String owner = server.clientOf(ownerId);
            if (owner != null) {
                server.sendTo(owner, tradePacket(trade).toString());
                MultiplayerLog.log().info(clientId + " traded at " + mirror.getName() + " (sent to its owner)");
            } else {
                server.registry.queueTrade(ownerId, trade);
                MultiplayerLog.log().info(clientId + " traded at " + mirror.getName() + " (its owner gets it when they're back)");
            }
        } catch (Exception e) {
            MultiplayerLog.log().error("Failed to apply a trade from " + clientId, e);
        }
    }

    /** A player is back (network thread): the trades at their colonies while they were away, for their game. */
    void deliverQueuedTrades(String clientId, String playerId) {
        JSONArray trades = server.registry.takeTrades(playerId);
        for (int i = 0; i < trades.length(); i++) {
            try {
                server.sendTo(clientId, tradePacket(trades.getJSONObject(i)).toString());
            } catch (JSONException e) {
                MultiplayerLog.log().error("Couldn't send a queued trade to " + clientId, e);
            }
        }
        if (trades.length() > 0) MultiplayerLog.log().info("Sent " + trades.length() + " trades at their colonies to " + clientId);
    }

    /** Every frame, from ServerScripts: visitors whose colony's owner is too slow get the stock it last sent. */
    void advance(float amount) {
        now += amount;
        String late = null;
        for (Map.Entry<String, Float> entry : waitingSince.entrySet()) {
            if (now - entry.getValue() >= OWNER_TIMEOUT) {
                late = entry.getKey();
                break;
            }
        }
        if (late == null) return;
        MultiplayerLog.log().warn("The owner of " + late + " didn't send its stock in time: visitors get the last one");
        answer(late); //one a frame: answer() changes the maps
    }

    /** Sends a colony's stock, as this game's mirror has it now, to everyone waiting for it. */
    private void answer(String marketId) {
        Set<String> visitors = waiting.remove(marketId);
        waitingSince.remove(marketId);
        if (visitors == null) return;
        MarketAPI mirror = ColonyMirrors.find(marketId);
        if (mirror == null) return;
        try {
            JSONObject snapshot = MarketSync.snapshot(mirror, false);
            for (String visitor : visitors) send(visitor, snapshot);
        } catch (JSONException e) {
            MultiplayerLog.log().error("Couldn't send the stock of " + mirror.getName(), e);
        }
    }

    private void send(String clientId, JSONObject snapshot) throws JSONException {
        JSONObject reply = new JSONObject();
        reply.put("commandId", "marketSnapshot");
        reply.put("snapshot", snapshot);
        server.sendTo(clientId, reply.toString());
    }

    private static JSONObject tradePacket(JSONObject trade) throws JSONException {
        JSONObject packet = new JSONObject();
        packet.put("commandId", "colonyTrade");
        packet.put("trade", trade);
        return packet;
    }
}
