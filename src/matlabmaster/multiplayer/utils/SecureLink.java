package matlabmaster.multiplayer.utils;

import matlabmaster.multiplayer.MultiplayerLog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The multiplayer connection's sockets: encrypted (TLS 1.3) when the multiplayer agent runs (the launcher starts
 * every game with it; it does the TLS, see agent SecureSockets: the game forbids mods what it takes), plain
 * otherwise, with a warning. An encrypted server and a plain client (or the other way) can't talk: the handshake
 * fails and the player is told why.
 */
public class SecureLink {
    private static final String SERVER_KEY = "multiplayer.secureServerSocket";
    private static final String CLIENT_KEY = "multiplayer.secureSocket";

    /** Whether this game's connections are encrypted (the agent runs). */
    public static boolean available() {
        return System.getProperties().get(CLIENT_KEY) instanceof BiFunction && System.getProperties().get(SERVER_KEY) instanceof Function;
    }

    @SuppressWarnings("unchecked")
    public static ServerSocket serverSocket(int port) throws IOException {
        Object factory = System.getProperties().get(SERVER_KEY);
        if (!(factory instanceof Function)) {
            MultiplayerLog.log().warn("The multiplayer agent isn't running: this server is NOT encrypted (start it with the multiplayer launcher)");
            return new ServerSocket(port);
        }
        try {
            ServerSocket socket = ((Function<Integer, ServerSocket>) factory).apply(port);
            MultiplayerLog.log().info("The connection is encrypted (TLS 1.3, this server's certificate)");
            return socket;
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * A connection to host:port: encrypted and connected (the server's certificate checked against the one this game
     * saw the first time), or, without the agent, a plain socket not connected yet.
     */
    @SuppressWarnings("unchecked")
    public static Socket socket(String host, int port) throws IOException {
        Object factory = System.getProperties().get(CLIENT_KEY);
        if (!(factory instanceof BiFunction)) {
            MultiplayerLog.log().warn("The multiplayer agent isn't running: this connection is NOT encrypted (start the game with the multiplayer launcher)");
            return new Socket();
        }
        try {
            return ((BiFunction<String, Integer, Socket>) factory).apply(host, port);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
