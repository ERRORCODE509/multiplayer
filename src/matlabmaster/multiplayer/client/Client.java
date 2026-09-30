package matlabmaster.multiplayer.client;

import com.fs.starfarer.api.Global;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.UserError;
import matlabmaster.multiplayer.server.Server;
import matlabmaster.multiplayer.ui.UI;
import matlabmaster.multiplayer.updates.WorldSync;
import matlabmaster.multiplayer.utils.FleetHelper;
import matlabmaster.multiplayer.utils.FleetSerializer;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public class Client {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean isConnected = false;
    private final CopyOnWriteArrayList<ClientListener> listeners = new CopyOnWriteArrayList<>();
    private boolean savedIdleWhileWindowNotVisible = true;
    private float savedCampaignSpeedupMult = 2f;
    public boolean isSelfHosted = false;
    public boolean isAuthority = false;
    public boolean wasPaused = false;
    public UI ui;
    public String clientId;

    public interface ClientListener {
        void onDisconnected();
        void onMessageReceived(String msg);
    }

    public void addListener(ClientListener listener) {
        listeners.add(listener);
    }

    public void connect(String ip, int port) throws IOException {
        if(Objects.equals(Global.getCurrentState().toString(), "TITLE")){
            throw new UserError("You cannot connect to a server while on the main menu, join any singleplayer game then try connecting");
        }
        socket = new Socket();
        socket.connect(new InetSocketAddress(ip, port), 5000);

        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

        //the server picks our id and sends it first; an id made from our own port number doesn't match the
        //server's behind a router (NAT)
        try {
            clientId = readWelcome();
        } catch (IOException | RuntimeException e) {
            try { socket.close(); } catch (IOException ignored) {}
            throw e;
        }
        Global.getSector().getPlayerFleet().setId(clientId);
        isConnected = true;

        // Apply multiplayer-only settings (reverted on disconnect)
        savedIdleWhileWindowNotVisible = Global.getSettings().getBoolean("idleWhileWindowNotVisible");
        savedCampaignSpeedupMult = Global.getSettings().getFloat("campaignSpeedupMult");
        Global.getSettings().setBoolean("idleWhileWindowNotVisible", false);
        Global.getSettings().setFloat("campaignSpeedupMult", 1f);

        // SUCCESS MESSAGE
        MultiplayerLog.log().info("CONNECTED SUCCESSFULLY TO SERVER " + ip + ":" + port);
        if(!isSelfHosted){
            try {
                //send our fleet to the server so that it knows about it
                JSONObject packet = new JSONObject();
                MultiplayerLog.log().info("SENDING PLAYER FLEET TO SERVER");
                packet.put("commandId","fleetSnapshot");
                packet.put("fleet",FleetSerializer.serializeFleet(Global.getSector().getPlayerFleet()));
                send(packet.toString());

                //prepare for all fleet syncing
                MultiplayerLog.log().warn("Joining replaces every AI fleet in this game with the host's. Play multiplayer on a copy of your save, not your main one.");
                MultiplayerLog.log().info("DESTROYING EXISTING FLEETS");
                FleetHelper.killAllFleetsExceptPlayer();

                //ask for all the sectors fleet snapshot
                MultiplayerLog.log().info("REQUESTING FLEETS SNAPSHOT");
                packet = new JSONObject();
                packet.put("commandId","requestAllFleetsSnapshot");
                send(packet.toString());

                //ask for current location orbits
                //todo update for all locations maybe if rly useful?
                WorldSync.requestOrbitSnapshotForLocation(Global.getSector().getPlayerFleet().getContainingLocation(),this);

            }catch (Exception e){
                MultiplayerLog.log().error("Handshake failed", e);
                disconnect();
            }
        }
        new Thread(() -> {
            try {
                String line;
                while (isConnected && (line = in.readLine()) != null) {
                    // Notify ALL listeners
                    for (ClientListener listener : listeners) {
                        try {
                            listener.onMessageReceived(line);
                        } catch (Exception e) {
                            MultiplayerLog.log().error("Exception in listener.onMessageReceived(): " + e.getMessage(), e);
                        }
                    }
                }
            } catch (IOException e) {
                // server probably cut out
            } finally {
                // Crucial : inform ui in case of error
                handleDisconnect();
            }
        }, "Client-Read-Thread").start();
    }

    /** Waits for the server's "welcome" (our id and its protocol version); a clear error if it never comes or doesn't match. */
    private String readWelcome() throws IOException {
        String line;
        try {
            socket.setSoTimeout(5000);
            line = in.readLine();
        } catch (SocketTimeoutException e) {
            throw new UserError("The server didn't greet this client. It's probably running an older version of the multiplayer mod; both players need the same version");
        }
        socket.setSoTimeout(0); //from now on the read thread waits for as long as it takes
        if (line == null) {
            throw new UserError("The server closed the connection before greeting this client");
        }
        try {
            JSONObject welcome = new JSONObject(line);
            if (!"welcome".equals(welcome.optString("commandId"))) {
                throw new UserError("The server didn't greet this client. It's probably running an older version of the multiplayer mod; both players need the same version");
            }
            int protocol = welcome.optInt("protocol", 0);
            if (protocol != Server.PROTOCOL_VERSION) {
                throw new UserError("Version mismatch: the server uses multiplayer protocol " + protocol + " and this client uses " + Server.PROTOCOL_VERSION + "; both players need the same version of the mod");
            }
            return welcome.getString("id");
        } catch (JSONException e) {
            throw new UserError("The server's greeting couldn't be read: " + e.getMessage());
        }
    }

    public void send(String message){
        out.println(message);
    }

    private void handleDisconnect() {
        if (isConnected) {
            isSelfHosted = false;
            isConnected = false;


            Global.getSettings().setBoolean("idleWhileWindowNotVisible", savedIdleWhileWindowNotVisible);//disable pause while window is not focused
            Global.getSettings().setFloat("campaignSpeedupMult", savedCampaignSpeedupMult);//disable speedup


            MultiplayerLog.log().info("DISCONNECTED FROM SERVER.");
            try { if (socket != null) socket.close(); } catch (IOException e) {MultiplayerLog.log().error("Unknown IO exception" + Arrays.toString(e.getStackTrace()));}

            // Notify ALL listeners
            for (ClientListener listener : listeners) {
                try {
                    listener.onDisconnected();
                } catch (Exception e) {
                    MultiplayerLog.log().error("Exception in listener.onDisconnected(): " + e.getMessage(), e);
                }
            }
        }
    }

    public void disconnect() {
        handleDisconnect();
    }

    public boolean isConnected() { return isConnected; }

}