package matlabmaster.multiplayer.agent;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Properties;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The multiplayer connection, encrypted (TLS 1.3), for the mod, which can't do it itself (the game forbids mods
 * file access, reflection and some of java.io: see SaveLoader). The mod gets plain java.net sockets through
 * System.getProperties():
 * - SERVER_KEY: Function<Integer, ServerSocket>, a TLS server socket on that port, with this game's server
 *   certificate (made once with the JRE's keytool: CERT_FILE and its password in PASS_FILE, in the game's folder);
 * - CLIENT_KEY: BiFunction<String, Integer, Socket>, a TLS connection to host:port. A server's certificate is
 *   pinned the first time this game connects to it (KNOWN_FILE: trust on first use, as ssh does); one that changes
 *   afterwards is refused (someone in between, or the server's certificate was remade: then remove its line).
 * Failures come back as UncheckedIOException, whose message the mod shows.
 */
public class SecureSockets {
    public static final String SERVER_KEY = "multiplayer.secureServerSocket";
    public static final String CLIENT_KEY = "multiplayer.secureSocket";
    static final Path CERT_FILE = Paths.get("multiplayer-server.p12");
    static final Path PASS_FILE = Paths.get("multiplayer-server.pass");
    static final Path KNOWN_FILE = Paths.get("multiplayer-known-servers.properties");
    private static final String[] PROTOCOLS = {"TLSv1.3"};
    private static final int CONNECT_TIMEOUT_MS = 5000;

    static void install() {
        System.getProperties().put(SERVER_KEY, (Function<Integer, ServerSocket>) SecureSockets::server);
        System.getProperties().put(CLIENT_KEY, (BiFunction<String, Integer, Socket>) SecureSockets::client);
    }

    static ServerSocket server(Integer port) {
        try {
            char[] password = serverPassword();
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(CERT_FILE)) {
                store.load(in, password);
            }
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, password);
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(keys.getKeyManagers(), null, new SecureRandom());
            SSLServerSocket socket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(port);
            socket.setEnabledProtocols(PROTOCOLS);
            return socket;
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("Couldn't start the encrypted server: " + e, e));
        }
    }

    /** The server certificate's password, making the certificate first if there's none (keytool, from this JRE). */
    private static char[] serverPassword() throws IOException, InterruptedException {
        if (Files.isRegularFile(CERT_FILE) && Files.isRegularFile(PASS_FILE)) {
            return new String(Files.readAllBytes(PASS_FILE), StandardCharsets.UTF_8).trim().toCharArray();
        }
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Files.deleteIfExists(CERT_FILE);
        String keytool = Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "keytool.exe" : "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "multiplayer", "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-validity", "36500", "-dname", "CN=Starsector multiplayer server",
                "-storetype", "PKCS12", "-keystore", CERT_FILE.toString(), "-storepass", password, "-keypass", password)
                .redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0 || !Files.isRegularFile(CERT_FILE)) {
            throw new IOException("keytool failed: " + new String(output, StandardCharsets.UTF_8).trim());
        }
        Files.write(PASS_FILE, password.getBytes(StandardCharsets.UTF_8));
        System.out.println("[multiplayer agent] made this server's certificate (" + CERT_FILE.toAbsolutePath() + ")");
        return password.toCharArray();
    }

    static Socket client(String host, Integer port) {
        try {
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(null, new TrustManager[] {new PinningTrust(host + ":" + port)}, new SecureRandom());
            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(plain, host, port, true);
            socket.setEnabledProtocols(PROTOCOLS);
            socket.startHandshake(); //here, so a refused certificate is this call's error, with its reason
            return socket;
        } catch (Exception e) {
            Throwable reason = e;
            while (reason.getCause() != null && !(reason instanceof CertificateException)) reason = reason.getCause();
            throw new UncheckedIOException(new IOException(reason instanceof CertificateException ? reason.getMessage()
                    : "Couldn't connect securely to " + host + ":" + port + " (" + e.getMessage() + "); is it an up to date multiplayer server?", e));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Trust on first use: a server's certificate is pinned the first time, and must be the same every time after. */
    private static final class PinningTrust implements X509TrustManager {
        private final String server;

        PinningTrust(String server) {
            this.server = server;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("The server at " + server + " sent no certificate");
            String fingerprint = fingerprint(chain[0]);
            synchronized (SecureSockets.class) {
                Properties known = new Properties();
                try {
                    if (Files.isRegularFile(KNOWN_FILE)) {
                        try (InputStream in = Files.newInputStream(KNOWN_FILE)) {
                            known.load(in);
                        }
                    }
                    String pinned = known.getProperty(server);
                    if (pinned == null) {
                        known.setProperty(server, fingerprint);
                        try (OutputStream out = Files.newOutputStream(KNOWN_FILE)) {
                            known.store(out, "Multiplayer servers this game has joined: their certificates (trust on first use)");
                        }
                        System.out.println("[multiplayer agent] first connection to " + server + ": its certificate is " + fingerprint);
                    } else if (!pinned.equals(fingerprint)) {
                        throw new CertificateException("The server at " + server + " isn't the one this game joined before (its certificate"
                                + " changed): someone may be in between. If its host remade it, remove its line from " + KNOWN_FILE.toAbsolutePath());
                    }
                } catch (IOException e) {
                    throw new CertificateException("Couldn't read or write " + KNOWN_FILE.toAbsolutePath() + ": " + e.getMessage(), e);
                }
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("not a server");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        private static String fingerprint(X509Certificate certificate) throws CertificateException {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) hex.append(String.format("%02X", b));
                return hex.toString();
            } catch (Exception e) {
                throw new CertificateException(e);
            }
        }
    }
}
