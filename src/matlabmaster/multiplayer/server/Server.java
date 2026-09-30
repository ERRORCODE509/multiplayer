package matlabmaster.multiplayer.server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import com.fs.starfarer.api.Global;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.UserError;
import matlabmaster.multiplayer.utils.CompatibilityUtility;
import matlabmaster.multiplayer.utils.FleetHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class Server {
    /** Bump whenever client and server messages change in a way an older version can't handle; checked on join. */
    public static final int PROTOCOL_VERSION = 1;

    private int port;
    private ServerSocket serverSocket;
    public volatile boolean isRunning = false; // volatile to ensure visibility between threads
    public final ConcurrentHashMap<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private ExecutorService threadPool;
    private  ServerListener listener;
    public ClientHandler authority;
    /** The host's game version, seed and mods, sent in every welcome so joiners can check they match. */
    private volatile JSONObject hostGame;

    public Server(int port) {
        this.port = port;
    }

    public void setPort(int port) {
        this.port = port;
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
                    broadcastExcept(clientId, message);
                    break;
                case "requestAllFleetsSnapshot":
                    JSONArray fleetsSnapshot = FleetHelper.getFleetsSnapshot();

                    packet.put("commandId", "handleAllFleetsSnapshot");
                    packet.put("fleets",fleetsSnapshot);
                    sendTo(clientId, String.valueOf(packet));
                    break;
                case "fleetSnapshot":
                    //noinspection DuplicateBranchesInSwitch
                    broadcastExcept(clientId, message);
                    break;
                case "globalFleetsUpdate":
                    //noinspection DuplicateBranchesInSwitch
                    broadcastExcept(clientId, message);
                    break;
                case "requestFleetSnapshot":
                    packet.put("commandId","requestFleetSnapshot");
                    packet.put("from",clientId);
                    packet.put("fleetId",json.getString("fleetId"));
                    authority.sendMessage(packet.toString());
                    //relay request to authority client
                    //send back reply to original asker
                    break;
                case "handleFleetSnapshotRequest":
                    sendTo(json.getString("to"),json.toString());
                    break;
                case "paused":
                    clients.get(clientId).isPaused = true;
                    MultiplayerLog.log().info("client " + clientId + " has paused");
                    if(clients.get(clientId) == authority){
                        authorityManager(this);
                    }
                    break;
                case "unpaused":
                    clients.get(clientId).isPaused = false;
                    MultiplayerLog.log().info("client " + clientId + " has unpaused");
                    break;
                case "requestPlayerFleetSnapshot":
                    //relay the information to concerned client
                    clients.get(json.getString("to")).sendMessage(json.toString());
                    break;
                case "requestOrbitSnapshotForLocation":
                    authority.sendMessage(json.toString());
                    break;
                case "handleOrbitSnapshotForLocation":
                    clients.get(json.getString("to")).sendMessage(json.toString());
                    break;
                case "handleServerTime":
                    broadcastExcept(clientId,message);
                    break;
                default:
                    MultiplayerLog.log().warn("Unknown command: " + commandId);
                    break;
            }

        } catch (Exception e) {
            MultiplayerLog.log().error("JSON Error from " + clientId + " : " + e.getMessage(), e);
        }
    }

    /** A random id no connected client has. Random rather than counted, so it can't match a fleet id left in someone's save by an earlier session. */
    private String newClientId() {
        String id;
        do {
            id = "User-" + UUID.randomUUID().toString().substring(0, 8);
        } while (clients.containsKey(id));
        return id;
    }

    private void authorityManager(Server server) throws JSONException {
        //if this method was called this means that the authority paused / left
        //to ensure smooth gameplay across clients a new authority must be set so that updates keep flowing
        //if all the clients are paused keep the authority the same
        ClientHandler newAuthority = null;
        ClientHandler lastClient = null;
        ClientHandler originalAuthority = server.authority;
        
        // Clean up: if current authority is no longer in clients, clear it
        if(originalAuthority != null && !server.clients.containsValue(originalAuthority)){
            originalAuthority = null;
            server.authority = null;
        }
        
        for (ClientHandler client : server.clients.values()){
            lastClient = client;
            if(!client.isPaused){
                newAuthority = client;
                break; // Found an unpaused client, use them as authority
            }
        }
        
        // If no unpaused client found, keep the current authority (or set to last client if authority was removed)
        if(newAuthority == null){
            if(authority != null && server.clients.containsValue(authority)){
                // Keep current authority if they're still in the clients list
                return;
            } else {
                // Authority was removed, set to last client (or null if no clients)
                // If no clients remain, authority will be null (handled by ServerScripts)
                authority = lastClient;
            }
        } else {
            authority = newAuthority;
        }

        if(authority != originalAuthority){
            if(originalAuthority != null){
                JSONObject packet = new JSONObject();
                packet.put("commandId","youAreNoLongerAuthority");
                originalAuthority.sendMessage(packet.toString());
            }
            JSONObject packet = new JSONObject();
            packet.put("commandId","youAreAuthority");
            authority.sendMessage(packet.toString());
        }
    }

    // --- INNER CLASS : GESTIONNAIRE DE CLIENT ---

    public class ClientHandler implements Runnable {
        private final Socket socket;
        private final String clientId;
        private PrintWriter out;
        private final Server server;
        public boolean isPaused;

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
            boolean wasAuthority = (this == server.authority);
            clients.remove(clientId);
            
            // If the authority disconnected, reassign authority
            if (wasAuthority) {
                try {
                    authorityManager(server);
                } catch (JSONException e) {
                    MultiplayerLog.log().error("Failed to reassign authority after disconnect: " + e.getMessage(), e);
                }
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