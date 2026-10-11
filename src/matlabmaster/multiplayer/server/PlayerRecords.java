package matlabmaster.multiplayer.server;

import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.utils.JsonDiffUtility;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each player's record: their character, fleet, officers, cargo, reputation, blueprints, storage and where they are,
 * as their own game describes it (utils/PlayerRecord), held by the world so that whatever save they join with is made
 * to match it. A joining player is sent theirs ("yourRecord"); until their game has applied it ("recordApplied"),
 * nothing it says about itself counts, since that is the state the record is about to replace. From then on their game
 * keeps it up to date: a diff every second ("playerRecordUpdate", against the revision it follows), all of it now and
 * then ("playerRecord"), and all of it when this asks ("requestPlayerRecord") because a diff didn't follow on from the
 * revision held here.
 *
 * The records are kept in memory on the game thread (the network threads hand everything over through
 * gameThreadTasks, with whose record it is read when the message came) and written to the world's PlayerRegistry as
 * JSON text when they changed: every 30 seconds, before the world is saved, when the player leaves and when hosting
 * stops. Only a save of the world keeps the registry (a dedicated server saves itself for that, see ServerScripts),
 * so rewriting a few hundred kilobytes on every diff would be wasted. The host's own client never has a record: its
 * game is the world.
 */
public class PlayerRecords {
    /** How often changed records are written to the registry (seconds). */
    private static final float FLUSH_INTERVAL = 30f;
    /** A player's game is asked for all of its record at most this often (milliseconds), however many diffs miss. */
    private static final long RESYNC_INTERVAL_MS = 5000L;

    /** A player's record as held here, and the revision their game counts it as. */
    private static final class Entry {
        JSONObject record;
        int rev;
        /** Changed since it was last written to the registry. */
        boolean dirty;
        /** A diff failed halfway: what's held is neither the old nor the new record until all of it comes again. */
        boolean broken;

        Entry(JSONObject record, int rev) {
            this.record = record;
            this.rev = rev;
            this.dirty = true;
        }
    }

    private final Server server;
    /** Connected players' records (player id -> record), game thread only. */
    private final Map<String, Entry> records = new HashMap<>();
    /** When each player's game was last asked for all of its record (player id -> ms), game thread only. */
    private final Map<String, Long> askedAt = new HashMap<>();
    /** Clients sent their record whose game hasn't applied it yet (network and game threads). */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    /** Pending clients whose ignored messages were logged already, so it's said once per client, not per message. */
    private final Set<String> dropLogged = ConcurrentHashMap.newKeySet();
    /**
     * A pending client's fleet (its fleetSnapshot message). Their game describes the fleet the record made just before
     * it says the record is applied, so the latest one is held until then rather than dropped: the world and the other
     * players then get that fleet, never one from before the record.
     */
    private final Map<String, JSONObject> heldSnapshots = new ConcurrentHashMap<>();
    private float timer = 0f;
    /** A player left since the world was last saved: their record is only in the registry, not yet on disk. */
    private boolean leftSinceSave = false;

    PlayerRecords(Server server) {
        this.server = server;
    }

    /** Hosting starts (before anyone joins): records held from another session or world are not this one's. */
    void clear() {
        records.clear();
        askedAt.clear();
        pending.clear();
        dropLogged.clear();
        heldSnapshots.clear();
        timer = 0f;
        leftSinceSave = false;
    }

    /**
     * Network thread (Server.hello): a player joined. Their record is sent from the game thread, so that one their
     * last session is still writing as they left (a quick reconnect) is written before it's read.
     */
    void joined(String clientId, String playerId, boolean local) {
        if (!local) pending.add(clientId);
        server.gameThreadTasks.add(() -> send(clientId, playerId, local));
    }

    /** Whether this client's game hasn't applied its record yet (any thread). */
    boolean isPending(String clientId) {
        return pending.contains(clientId);
    }

    /**
     * Network thread: true if this message from the client is about its own state from before its record was applied,
     * or something it did to the world with that state (a trade, a raid, a battle, salvage), so it must be ignored
     * (logged once per client). The record then replaces that state, so the world mustn't keep what it did either.
     */
    boolean dropWhilePending(String clientId, String commandId) {
        //a connection that hasn't said hello isn't a player yet: what its game did happened before the record too
        if (!pending.contains(clientId) && server.clientPlayers.containsKey(clientId)) return false;
        if (dropLogged.add(clientId)) {
            MultiplayerLog.log().info(server.who(clientId) + "'s game hasn't applied their record yet: what it says about itself or does to the world (" + commandId + "...) is ignored until it has");
        }
        return true;
    }

    /** Network thread: true if the client's fleet is held until its record is applied (see heldSnapshots). */
    boolean holdWhilePending(String clientId, JSONObject snapshot) {
        if (!pending.contains(clientId)) return false;
        heldSnapshots.put(clientId, snapshot);
        return true;
    }

    /**
     * Network thread: the client's game applied its record ("recordApplied"), so what it says about itself counts
     * from now on. Returns its fleet held meanwhile (null if none), to be handled as if it came now.
     */
    JSONObject applied(String clientId) {
        pending.remove(clientId);
        dropLogged.remove(clientId);
        return heldSnapshots.remove(clientId);
    }

    /** Any thread: the client is gone (its record itself is written by left(), on the game thread). */
    void forget(String clientId) {
        pending.remove(clientId);
        dropLogged.remove(clientId);
        heldSnapshots.remove(clientId);
    }

    /** Game thread: a joining player's record, or that they have none yet ("local": the host's own client). */
    private void send(String clientId, String playerId, boolean local) {
        //they may have left since
        if (!playerId.equals(server.clientPlayers.get(clientId))) return;
        try {
            JSONObject packet = new JSONObject().put("commandId", "yourRecord").put("playerId", playerId).put("local", local);
            if (local) {
                server.sendTo(clientId, packet.toString());
                return;
            }
            JSONObject record = stored(playerId);
            if (record != null) packet.put("record", record);
            server.sendTo(clientId, packet.toString());
            if (record != null) MultiplayerLog.log().info(server.who(clientId) + "'s record sent (" + ships(record) + " ships)");
            else MultiplayerLog.log().info(server.who(clientId) + " joined with no record yet: their game's state becomes it");
        } catch (JSONException e) {
            MultiplayerLog.log().error("Couldn't send " + server.who(clientId) + " their record", e);
        }
    }

    /** A player's latest record: held here (not written to the registry yet) or the registry's; null if none. */
    private JSONObject stored(String playerId) {
        Entry entry = records.get(playerId);
        if (entry != null && !entry.broken) return entry.record;
        try {
            return server.registry.record(playerId);
        } catch (JSONException e) {
            //never lost: kept aside for whoever looks into it, while their game's state becomes their record
            server.registry.put("recordUnreadable:" + playerId, server.registry.get("record:" + playerId));
            MultiplayerLog.log().error("The record of " + name(playerId) + " can't be read (kept as recordUnreadable:" + playerId + "): they join as if they had none", e);
            return null;
        }
    }

    /** Game thread: their game applied its record ("recordApplied"), and this is what it now has: theirs. */
    void adopt(String clientId, String playerId, JSONObject record, int rev) {
        records.put(playerId, new Entry(record, rev));
        askedAt.remove(playerId);
        MultiplayerLog.log().info(server.who(clientId) + "'s game applied their record (" + ships(record) + " ships): kept up to date from now on");
    }

    /** Game thread: all of a player's record ("playerRecord"), sent now and then or when asked. */
    void replace(String clientId, String playerId, JSONObject record, int rev) {
        Entry entry = records.get(playerId);
        if (entry == null) {
            //only after they left (written and dropped already): late, nothing to replace
            if (!playerId.equals(server.clientPlayers.get(clientId))) return;
            entry = new Entry(record, rev);
            records.put(playerId, entry);
        }
        if (entry.broken) MultiplayerLog.log().info(server.who(clientId) + "'s record is whole again");
        entry.record = record;
        entry.rev = rev;
        entry.dirty = true;
        entry.broken = false;
    }

    /**
     * Game thread: what changed in a player's record since revision base ("playerRecordUpdate"). One that doesn't
     * follow on from the revision held here (a message lost, a diff that failed) can't be applied: their game is
     * asked for all of it instead.
     */
    void update(String clientId, String playerId, int base, int rev, JSONObject changes) {
        Entry entry = records.get(playerId);
        if (entry == null || entry.broken || entry.rev != base) {
            //after they left, the record was written and dropped: nobody to ask
            if (entry == null && !playerId.equals(server.clientPlayers.get(clientId))) return;
            resync(clientId, playerId, entry == null ? "no record held" : entry.broken ? "an earlier diff failed" : "diff on revision " + base + ", have " + entry.rev);
            return;
        }
        try {
            JsonDiffUtility.apply(entry.record, changes);
            entry.rev = rev;
            entry.dirty = true;
        } catch (Exception e) {
            //what's held may be partly changed: never written to the registry like that (it keeps the last whole one)
            entry.broken = true;
            MultiplayerLog.log().error("Couldn't apply a change to " + server.who(clientId) + "'s record (revision " + base + " to " + rev + "): asking for all of it", e);
            resync(clientId, playerId, "a diff failed");
        }
    }

    /** Asks a player's game for all of its record, at most every few seconds. */
    private void resync(String clientId, String playerId, String why) {
        long now = System.currentTimeMillis();
        Long last = askedAt.get(playerId);
        if (last != null && now - last < RESYNC_INTERVAL_MS) return;
        askedAt.put(playerId, now);
        try {
            server.sendTo(clientId, new JSONObject().put("commandId", "requestPlayerRecord").toString());
            MultiplayerLog.log().info(server.who(clientId) + "'s record is out of step (" + why + "): asked for all of it");
        } catch (JSONException e) {
            MultiplayerLog.log().error("Couldn't ask " + server.who(clientId) + " for their record", e);
        }
    }

    /**
     * Game thread: a player left (queued by Server's forget(), after anything they sent before leaving): their record
     * is written to the registry and no longer held here.
     */
    void left(String playerId) {
        askedAt.remove(playerId);
        Entry entry = records.remove(playerId);
        if (entry == null) return;
        leftSinceSave = true;
        if (entry.broken) {
            MultiplayerLog.log().warn(name(playerId) + " left with their record out of step: it stays as it was last saved");
        } else if (entry.dirty) {
            server.registry.setRecord(playerId, entry.record);
            MultiplayerLog.log().info(name(playerId) + "'s record saved to the world (" + ships(entry.record) + " ships)");
        }
    }

    /** Game thread, every frame (ServerScripts): changed records are written to the registry every 30 seconds. */
    void advance(float amount) {
        timer += amount;
        if (timer < FLUSH_INTERVAL) return;
        timer = 0f;
        int written = flush();
        if (written > 0) MultiplayerLog.log().debug(written + " player record(s) written to the world's registry");
    }

    /**
     * Game thread: every changed record written to the registry now (the world is about to be saved, or hosting
     * stopped). A broken one isn't: the registry keeps the last whole one. Returns how many were written.
     */
    int flush() {
        int written = 0;
        for (Map.Entry<String, Entry> e : records.entrySet()) {
            Entry entry = e.getValue();
            if (!entry.dirty || entry.broken) continue;
            server.registry.setRecord(e.getKey(), entry.record);
            entry.dirty = false;
            written++;
        }
        return written;
    }

    /** Game thread: whether a player left since the world was last saved (see ServerScripts' saves). */
    boolean leftSinceSave() {
        return leftSinceSave;
    }

    /** Game thread: the world was just saved, with every record (flush() ran before it, from beforeGameSave). */
    void worldSaved() {
        leftSinceSave = false;
    }

    /**
     * Game thread (ServerScripts, once hosting stopped): the work queued for the players leaving is dropped then, so
     * their records are written here, and nobody is left holding one.
     */
    void stopped() {
        int written = flush();
        if (written > 0) MultiplayerLog.log().info(written + " player record(s) saved to the world as hosting stopped");
        clear();
    }

    /** How many ships a record's fleet has, for the logs. */
    private static int ships(JSONObject record) {
        JSONObject fleet = record.optJSONObject("fleet");
        return fleet == null ? 0 : fleet.length();
    }

    /** A player's name, for the logs once they're gone (their player id if it isn't known). */
    private String name(String playerId) {
        String name = server.registry.get("name:" + playerId);
        return name != null ? name : playerId;
    }
}
