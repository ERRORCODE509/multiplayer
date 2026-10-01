package matlabmaster.multiplayer.server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import matlabmaster.multiplayer.updates.BattleSync;
import matlabmaster.multiplayer.updates.MarketSync;
import matlabmaster.multiplayer.utils.PlayerFactions;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.UserError;
import matlabmaster.multiplayer.utils.CompatibilityUtility;
import matlabmaster.multiplayer.utils.FleetHelper;
import matlabmaster.multiplayer.utils.FleetSerializer;
import matlabmaster.multiplayer.updates.WorldSync;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.campaign.Faction;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

public class Server {
    /** Bump whenever client and server messages change in a way an older version can't handle; checked on join. */
    public static final int PROTOCOL_VERSION = 5; //2: the server's game is the only authority. 3: player factions, markets. 4: hello (player ids), colonies. 5: trade at colonies

    private int port;
    private ServerSocket serverSocket;
    public volatile boolean isRunning = false; // volatile to ensure visibility between threads
    public final ConcurrentHashMap<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private ExecutorService threadPool;
    private  ServerListener listener;
    /** How the server names itself in "to" / "from" fields. */
    public static final String SERVER_ID = "server";
    /**
     * The game that hosts the server is the only authority: its clock, scripts and NPC fleets are the world, and
     * ServerScripts sends them to the clients. "Host as dedicated": nobody plays in this game, so it keeps its
     * own copies of the players' fleets (in "host current game" mode the host's own client does that).
     */
    private volatile boolean dedicated;
    /** "Host current game": the host's own client, which shares this game (the world), so it's never sent the world. */
    private volatile String localClientId;
    /** Player fleets a dedicated server has asked their client for in full, so it asks only once. */
    private final Set<String> pendingPlayerSnapshots = ConcurrentHashMap.newKeySet();
    /**
     * What each player is in a dialog with (client id -> interaction). In single player the dialog pauses the game;
     * here the world runs on, so until the dialog closes ServerScripts holds an NPC fleet they talk to where their
     * game saw it, and keeps their fleet by a planet or station they talk to, as their game does.
     */
    public final Map<String, Interaction> interactions = new ConcurrentHashMap<>();

    /**
     * What a player is in a dialog with (an NPC fleet, a planet...), where their game saw it then, and where their
     * own fleet was (either null if their game didn't say).
     */
    public static final class Interaction {
        public final String target;
        public final Vector2f seenAt;
        public final Vector2f playerAt;

        Interaction(String target, Vector2f seenAt, Vector2f playerAt) {
            this.target = target;
            this.seenAt = seenAt;
            this.playerAt = playerAt;
        }
    }
    /** Each connected player's faction in this game and the others (client id -> mp_player_N), see PlayerFactions. */
    public final Map<String, String> clientFactions = new ConcurrentHashMap<>();
    /** Each connected player's permanent player id (client id -> player id), from their hello. */
    public final Map<String, String> clientPlayers = new ConcurrentHashMap<>();
    /** Every player who has joined this world: their factions, looks and colonies, kept in its save. */
    public final PlayerRegistry registry = new PlayerRegistry();
    /** Gives players markets' stock and takes their trades, players' colonies included (game thread). */
    public final ServerMarkets markets = new ServerMarkets(this);
    /** Sends the world's faction relations and the players' factions to the clients (game thread). */
    public final ServerFactionSync factionSync = new ServerFactionSync(this);
    /** Connected players this game has no copy of the fleet of (game thread only), so it's only logged once. */
    private final Set<String> missingPlayerFleets = new HashSet<>();
    /** The host's game version, seed and mods, sent in every welcome so joiners can check they match. */
    private volatile JSONObject hostGame;
    /**
     * Work that reads or changes the game, handed over by the network threads (the game isn't thread safe).
     * ServerScripts runs it on the game thread every frame.
     */
    public final ConcurrentLinkedQueue<Runnable> gameThreadTasks = new ConcurrentLinkedQueue<>();

    public Server(int port) {
        this.port = port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setDedicated(boolean dedicated) {
        this.dedicated = dedicated;
    }

    public boolean isDedicated() {
        return dedicated;
    }

    /** The connected client of a player (their permanent player id), or null if they're offline. */
    public String clientOf(String playerId) {
        if (playerId == null) return null;
        for (Map.Entry<String, String> entry : clientPlayers.entrySet()) {
            if (playerId.equals(entry.getValue())) return entry.getKey();
        }
        return null;
    }

    /** Whether this is the host's own client, which shares this game ("host current game"). */
    public boolean isLocalClient(String clientId) {
        return clientId != null && clientId.equals(localClientId);
    }

    /** Called by the host's own client (same game) once it knows its id. */
    public void setLocalClientId(String localClientId) {
        this.localClientId = localClientId;
    }

    public interface ServerListener{
        void onServerStopped();
    }

    public void setListener(ServerListener listener) {
        this.listener = listener;
    }

    public void start() {
        if(Objects.equals(Global.getCurrentState().toString(), "TITLE")){
            throw new UserError("You cannot host a server while on the main menu, join any singleplayer game then try hosting");
        }
        if (isRunning) return;
        try {
            hostGame = CompatibilityUtility.describeThisGame(); //the loaded game can't change while hosting (quitting to the menu stops the server)
        } catch (JSONException e) {
            throw new UserError("Couldn't read this game's version, seed and mods: " + e.getMessage());
        }
        registry.load(); //before anyone can join: who's who in this world
        isRunning = true;
        threadPool = Executors.newCachedThreadPool();

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                MultiplayerLog.log().info("Server started on port " + port);

                while (isRunning) {
                    try {
                        Socket socket = serverSocket.accept();
                        if (!isRunning) break; // safety if stop() is called on accept

                        //the server picks the id and sends it first ("welcome"): ids made from port numbers don't
                        //match on both ends behind a router (NAT), and two machines can use the same local port
                        String clientId = newClientId();
                        ClientHandler handler = new ClientHandler(socket, clientId, this);
                        try {
                            JSONObject welcome = new JSONObject();
                            welcome.put("commandId","welcome");
                            welcome.put("id",clientId);
                            welcome.put("protocol",PROTOCOL_VERSION);
                            welcome.put("game",hostGame);
                            handler.sendMessage(welcome.toString());
                        } catch (JSONException e) {
                            MultiplayerLog.log().error("failed to welcome " + clientId, e);
                            handler.closeConnection();
                            continue;
                        }
                        clients.put(clientId, handler);
                        JSONObject packet;

                        MultiplayerLog.log().info("[JOINED] " + clientId + " is connected");
                        try {
                            packet = new JSONObject();
                            packet.put("commandId","playerJoined");
                            packet.put("id",clientId);
                        }catch (Exception e){
                            MultiplayerLog.log().error("failed to broadcast player joined");
                        }
                        threadPool.execute(handler);

                    } catch (IOException e) {
                        if (isRunning) MultiplayerLog.log().error("Accept : " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                if (isRunning) MultiplayerLog.log().error("Port " + port + " unavailable.");
            } finally {
                internalStop();
            }
        }, "Server-Main-Thread").start();
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        MultiplayerLog.log().info("SERVER STOPPING...");
        internalStop();
    }

    private synchronized void internalStop() {
        // do nothing because server is already stopped
        if (serverSocket == null || serverSocket.isClosed()) return;

        try {
            serverSocket.close();
            for (ClientHandler handler : clients.values()) {
                handler.closeConnection();
            }
            clients.clear();
            if (threadPool != null) threadPool.shutdownNow();

            MultiplayerLog.log().info("SERVER STOPPED.");

            if (listener != null) {
                listener.onServerStopped();
            }
        } catch (IOException e) {
            // no need for logs here
        } finally {
            serverSocket = null; // Important avoids double logging
        }
    }

    public void processIncomingMessage(String clientId, String message) {
        try {
            // 1. Instanciation de l'objet JSON (pas de réflexion ici, juste du parsing de texte)
            JSONObject json = new JSONObject(message);
            //System.out.println("[DEBUG] message recieved from " + clientId + " : " + message);
            // 2. Vérification de la commande
            if (!json.has("commandId")) return;

            String commandId = json.getString("commandId");
            JSONObject packet = new JSONObject();
            // 3. Dispatcher
            switch (commandId) {
                case "playerFleetUpdate":
                    rewriteOwnFaction(clientId, json);
                    broadcastExcept(clientId, json.toString());
                    if (dedicated) gameThreadTasks.add(() -> applyPlayerFleetUpdate(clientId, json));
                    break;
                case "requestAllFleetsSnapshot":
                    //reading every fleet in the sector: done on the game thread by ServerScripts, not here
                    gameThreadTasks.add(() -> {
                        try {
                            JSONObject reply = new JSONObject();
                            reply.put("commandId", "handleAllFleetsSnapshot");
                            //only the players' fleets: NPC fleets come with the next world update, only those this
                            //client can see. A dedicated server's own player fleet isn't a player: left out
                            reply.put("fleets", FleetHelper.getPlayerFleetsSnapshot(!dedicated));
                            if (!dedicated && localClientId != null) rewriteOwnFaction(localClientId, reply); //the host's own fleet
                            sendTo(clientId, String.valueOf(reply));
                        } catch (Exception e) {
                            MultiplayerLog.log().error("Failed to build the fleets snapshot for " + clientId, e);
                        }
                    });
                    break;
                case "fleetSnapshot":
                    rewriteOwnFaction(clientId, json);
                    broadcastExcept(clientId, json.toString());
                    if (dedicated) gameThreadTasks.add(() -> spawnPlayerFleet(json));
                    break;
                case "requestFleetSnapshot":
                    //the world is this game: answer from it, on the game thread
                    String fleetId = json.getString("fleetId");
                    gameThreadTasks.add(() -> {
                        try {
                            SectorEntityToken fleet = Global.getSector().getEntityById(fleetId);
                            if (!(fleet instanceof CampaignFleetAPI)) return; //gone already; the next update says so
                            JSONObject reply = new JSONObject();
                            reply.put("commandId", "handleFleetSnapshotRequest");
                            reply.put("to", clientId);
                            reply.put("fleet", FleetSerializer.serializeFleet((CampaignFleetAPI) fleet));
                            if (!dedicated && localClientId != null) rewriteOwnFaction(localClientId, reply); //the host's own fleet
                            sendTo(clientId, reply.toString());
                        } catch (Exception e) {
                            MultiplayerLog.log().error("Failed to send fleet " + fleetId + " to " + clientId, e);
                        }
                    });
                    break;
                case "handleFleetSnapshotRequest":
                    rewriteOwnFaction(clientId, json); //the sender's own fleet
                    if (SERVER_ID.equals(json.optString("to"))) { //a player's fleet a dedicated server asked for
                        if (dedicated) gameThreadTasks.add(() -> spawnPlayerFleet(json));
                    } else {
                        sendTo(json.getString("to"), json.toString());
                    }
                    break;
                case "paused":
                case "unpaused":
                    //a player in a dialog: the world runs on, except what they're talking to (see interactions)
                    String target = "paused".equals(commandId) ? json.optString("interactionTarget", null) : null;
                    if (target != null) {
                        Vector2f seenAt = json.has("targetX") ? new Vector2f((float) json.getDouble("targetX"), (float) json.getDouble("targetY")) : null;
                        Vector2f playerAt = json.has("selfX") ? new Vector2f((float) json.getDouble("selfX"), (float) json.getDouble("selfY")) : null;
                        interactions.put(clientId, new Interaction(target, seenAt, playerAt));
                    } else {
                        interactions.remove(clientId);
                    }
                    MultiplayerLog.log().info("client " + clientId + " has " + commandId + (target != null ? " (talking to " + target + ")" : ""));
                    break;
                case "requestPlayerFleetSnapshot":
                    //relay the information to concerned client (who may have left already)
                    sendTo(json.getString("to"), json.toString());
                    break;
                case "requestOrbitSnapshotForLocation":
                    String location = json.getString("location");
                    gameThreadTasks.add(() -> {
                        try {
                            LocationAPI loc = "hyperspace".equals(location) ? Global.getSector().getHyperspace() : Global.getSector().getStarSystem(location);
                            if (loc == null) return;
                            JSONObject reply = new JSONObject();
                            reply.put("commandId", "handleOrbitSnapshotForLocation");
                            reply.put("to", clientId);
                            reply.put("orbits", WorldSync.buildOrbitSnapshot(loc));
                            sendTo(clientId, reply.toString());
                        } catch (Exception e) {
                            MultiplayerLog.log().error("Failed to send the orbits of " + location + " to " + clientId, e);
                        }
                    });
                    break;
                case "hello":
                    //handled here, before this client's next message: their fleet's faction depends on it
                    hello(clientId, json);
                    break;
                case "colonies":
                    //the player's colonies as their game describes them (it runs them)
                    JSONArray colonies = json.getJSONArray("colonies");
                    gameThreadTasks.add(() -> factionSync.colonies(clientId, colonies));
                    break;
                case "playerFaction":
                    //the player's reputation (their own game decides it) and how their faction looks
                    gameThreadTasks.add(() -> factionSync.playerFaction(clientId, json));
                    break;
                case "requestMarket":
                    //a player opened a market: its stock (see ServerMarkets)
                    String marketId = json.getString("marketId");
                    gameThreadTasks.add(() -> markets.request(clientId, marketId));
                    break;
                case "marketTrade":
                    //what a player bought and sold there
                    JSONObject trade = json.getJSONObject("trade");
                    gameThreadTasks.add(() -> markets.trade(clientId, trade));
                    break;
                case "battleResult":
                    //a player's battle against the world's NPC fleets: they lose the same ships here
                    JSONObject result = json.getJSONObject("result");
                    gameThreadTasks.add(() -> {
                        try {
                            BattleSync.apply(clientId, result);
                        } catch (Exception e) {
                            MultiplayerLog.log().error("Failed to apply a battle of " + clientId, e);
                        }
                    });
                    break;
                case "colonyStock":
                    //a player's colony's stock, from their game (it holds the real one)
                    JSONObject snapshot = json.getJSONObject("snapshot");
                    gameThreadTasks.add(() -> markets.stock(clientId, snapshot));
                    break;
                default:
                    MultiplayerLog.log().warn("Unknown command: " + commandId);
                    break;
            }

        } catch (Exception e) {
            MultiplayerLog.log().error("JSON Error from " + clientId + " : " + e.getMessage(), e);
        }
    }

    /**
     * A joining player says who they are (their permanent player id, the first thing their game sends): they get
     * their own faction, reserved for them in this world for good (see PlayerRegistry), or a new one. With every
     * player faction someone's, they're independent: they can still play, but share the independents' reputation,
     * and their own reputation and colonies aren't applied. The same player connected twice (one save opened in two
     * games) is refused: both would be the same faction.
     */
    private synchronized void hello(String clientId, JSONObject json) throws JSONException {
        String playerId = json.getString("playerId");
        String name = json.optString("name", "?");
        if (clientPlayers.containsValue(playerId)) {
            MultiplayerLog.log().warn(name + " (" + playerId + ") is already connected: refused " + clientId + " (the same save in two games?)");
            ClientHandler handler = clients.get(clientId);
            if (handler != null) handler.closeConnection();
            return;
        }
        clientPlayers.put(clientId, playerId);
        String faction = registry.reserveFaction(playerId, name);
        if (faction == null) {
            MultiplayerLog.log().warn("All " + PlayerFactions.SLOT_COUNT + " player factions belong to someone: " + name + " plays as independent");
            faction = Factions.INDEPENDENT;
        }
        clientFactions.put(clientId, faction);
        factionSync.joined(clientId);
        JSONObject reply = new JSONObject();
        reply.put("commandId", "yourFaction");
        reply.put("faction", faction);
        sendTo(clientId, reply.toString());
        MultiplayerLog.log().info(clientId + " is " + name + " (" + playerId + "), faction " + faction);
        markets.deliverQueuedTrades(clientId, playerId); //visitors' trades at their colonies while they were away
    }

    /** A player's fleet data (their fleet, its officers, or a diff) with their "player" faction made their player faction. */
    private void rewriteOwnFaction(String clientId, JSONObject json) throws JSONException {
        String faction = clientFactions.get(clientId);
        if (faction != null) PlayerFactions.rewriteOwnFaction(json, faction);
    }

    /** A random id no connected client has. Random rather than counted, so it can't match a fleet id left in someone's save by an earlier session. */
    private String newClientId() {
        String id;
        do {
            id = "User-" + UUID.randomUUID().toString().substring(0, 8);
        } while (clients.containsKey(id));
        return id;
    }

    /** Every client except the host's own one, by id: who gets the world's updates, each only what it can see. */
    public Map<String, java.util.function.Consumer<String>> worldClients() {
        Map<String, java.util.function.Consumer<String>> world = new LinkedHashMap<>();
        for (Map.Entry<String, ClientHandler> entry : clients.entrySet()) {
            if (!entry.getKey().equals(localClientId)) world.put(entry.getKey(), entry.getValue()::sendMessage);
        }
        return world;
    }

    /** Every client except the host's own one (which shares this game): who gets the world's updates. */
    public void broadcastWorld(String message) {
        for (Map.Entry<String, ClientHandler> entry : clients.entrySet()) {
            if (!entry.getKey().equals(localClientId)) {
                entry.getValue().sendMessage(message);
            }
        }
    }

    /** Dedicated mode, game thread: apply a player's fleet update to this game's copy of their fleet, or ask for all of it. */
    private void applyPlayerFleetUpdate(String clientId, JSONObject update) {
        try {
            String fleetId = update.getString("fleetId");
            SectorEntityToken fleet = Global.getSector().getEntityById(fleetId);
            if (fleet instanceof CampaignFleetAPI) {
                FleetSerializer.applyFleetDiff((CampaignFleetAPI) fleet, update.getJSONObject("changes"));
            } else if (pendingPlayerSnapshots.add(fleetId)) {
                JSONObject ask = new JSONObject();
                ask.put("commandId", "requestPlayerFleetSnapshot");
                ask.put("to", clientId);
                ask.put("from", SERVER_ID);
                sendTo(clientId, ask.toString());
            }
        } catch (Exception e) {
            MultiplayerLog.log().error("Failed to apply a fleet update from " + clientId, e);
        }
    }

    /**
     * Dedicated mode, game thread: asks each connected player whose fleet this game has no copy of for all of it.
     * Without a copy that player is sent no NPC fleets at all, and a fleet update is what normally makes this game
     * ask; a player who isn't moving sends none, so a copy lost from this game would never come back.
     */
    public void requestMissingPlayerFleets() {
        for (String clientId : worldClients().keySet()) {
            if (Global.getSector().getEntityById(clientId) instanceof CampaignFleetAPI) {
                if (missingPlayerFleets.remove(clientId)) MultiplayerLog.log().info("Have " + clientId + "'s fleet again");
                continue;
            }
            if (missingPlayerFleets.add(clientId)) {
                MultiplayerLog.log().warn("No copy of " + clientId + "'s fleet in this game: it sees no NPC fleets until it's back; asking for it");
            }
            try {
                JSONObject ask = new JSONObject();
                ask.put("commandId", "requestPlayerFleetSnapshot");
                ask.put("to", clientId);
                ask.put("from", SERVER_ID);
                sendTo(clientId, ask.toString());
            } catch (Exception e) {
                MultiplayerLog.log().error("Failed to ask " + clientId + " for its fleet", e);
            }
        }
    }

    /** Dedicated mode, game thread: add a copy of a player's fleet to this game, so NPC fleets can see it. */
    private void spawnPlayerFleet(JSONObject message) {
        try {
            JSONObject fleet = message.getJSONObject("fleet");
            String fleetId = fleet.getString("id");
            pendingPlayerSnapshots.remove(fleetId);
            if (Global.getSector().getEntityById(fleetId) instanceof CampaignFleetAPI) return;
            FleetSerializer.unSerializeFleet(fleet, Global.getFactory().createEmptyFleet(Faction.NO_FACTION, true));
        } catch (Exception e) {
            MultiplayerLog.log().error("Failed to add a player's fleet to the server's game", e);
        }
    }

    // --- INNER CLASS : GESTIONNAIRE DE CLIENT ---

    public class ClientHandler implements Runnable {
        private final Socket socket;
        private final String clientId;
        private PrintWriter out;
        private final Server server;

        public ClientHandler(Socket socket, String clientId, Server server) throws IOException {
            this.socket = socket;
            this.clientId = clientId;
            this.server = server;
            //created here rather than in run() so the welcome, and anything broadcast before run() starts, isn't lost.
            //UTF-8 to match the reader on the other end (Java 17's default on Windows is not UTF-8). Not through an
            //OutputStreamWriter: the game refuses to load java.io classes outside a short allowed list for mods
            this.out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        }

        @Override
        public void run() {
            Thread.currentThread().setContextClassLoader(Server.class.getClassLoader());
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                String input;
                // La boucle s'arrête si le client coupe (input == null) OU si le serveur s'arrête
                while (server.isRunning && (input = in.readLine()) != null) {
                    server.processIncomingMessage(clientId, input);
                }
            } catch (IOException e) {
                // read error often due to brutal connexion lost
            } finally {
                closeConnection();
                JSONObject packet = new JSONObject();
                try {
                    packet.put("commandId","playerLeft");
                    packet.put("id",clientId);
                    broadcast(String.valueOf(packet));
                }catch (Exception e){
                    MultiplayerLog.log().error("Failed to broadcast playerLeft of leaving player", e);
                }
            }
        }

        public void sendMessage(String msg) {
            if (out != null && !socket.isClosed()) {
                out.println(msg);
            }
        }

        public void closeConnection() {
            clients.remove(clientId);
            interactions.remove(clientId); //a player who left isn't talking to anyone
            clientPlayers.remove(clientId);
            String faction = clientFactions.remove(clientId);
            if (faction != null) gameThreadTasks.add(() -> factionSync.freed(faction)); //for the next player
            if (clientId.equals(localClientId)) localClientId = null;
            if (dedicated) { //remove this game's copy of their fleet
                pendingPlayerSnapshots.remove(clientId);
                gameThreadTasks.add(() -> FleetHelper.removeFleetById(clientId));
            }

            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                // Déjà fermé
            }
        }
    }

    public void broadcast(String message) {
        for (ClientHandler handler : clients.values()) {
            handler.sendMessage(message);
        }
    }

    public void broadcastExcept(String senderId, String message) {
        for (Map.Entry<String, ClientHandler> entry : clients.entrySet()) {
            if (!entry.getKey().equals(senderId)) {
                entry.getValue().sendMessage(message);
            }
        }
    }

    public void sendTo(String clientId, String message) {
        ClientHandler handler = clients.get(clientId);
        if (handler != null) {
            handler.sendMessage(message);
        }
    }
}