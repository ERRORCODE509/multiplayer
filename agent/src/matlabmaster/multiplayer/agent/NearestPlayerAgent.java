package matlabmaster.multiplayer.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Java agent (-javaagent:MultiplayerAgent.jar, added by the multiplayer launcher): as the vanilla fleet managers
 * load, rewrites their "distance to the player" calls to NearestPlayer, so they measure to the nearest connected
 * player; and as the campaign engine loads, routes its location updates through FullRateLocations, so every
 * location a player is in runs every frame. Nothing else is touched, and nothing on disk changes.
 *
 * Option: -javaagent:MultiplayerAgent.jar=classes=a/b/C;d/e/F replaces the list of classes to patch (internal
 * names), e.g. to add another mod's fleet manager.
 */
public class NearestPlayerAgent {
    static final String MISC = "com/fs/starfarer/api/util/Misc";
    static final String HELPER = "matlabmaster/multiplayer/agent/NearestPlayer";
    static final String ENGINE = "com/fs/starfarer/campaign/CampaignEngine";
    static final String FULL_RATE = "matlabmaster/multiplayer/agent/FullRateLocations";
    /** The location calls in CampaignEngine.advance (0.98a): fast-advance branch, then the normal one. */
    static final int ENGINE_CALLS = 17;
    static final String[] DEFAULT_CLASSES = {
        "com/fs/starfarer/api/impl/campaign/fleets/RouteManager",
        "com/fs/starfarer/api/impl/campaign/fleets/SourceBasedFleetManager",
        "com/fs/starfarer/api/impl/campaign/fleets/SeededFleetManager",
        "com/fs/starfarer/api/impl/campaign/fleets/DisposableFleetManager",
        "com/fs/starfarer/api/impl/campaign/fleets/PlayerVisibleFleetManager",
    };

    public static void premain(String args, Instrumentation inst) {
        Set<String> classes = new HashSet<>(Arrays.asList(DEFAULT_CLASSES));
        if (args != null && args.startsWith("classes=")) {
            classes = new HashSet<>(Arrays.asList(args.substring("classes=".length()).split(";")));
        }
        //Everything has to be loaded before the transformer is installed. With some JVM flags (the game's vmparams)
        //even ConcurrentHashMap's internals aren't loaded yet; loading one later goes through the transformer, which
        //needs it itself: ClassCircularityError, and the JVM refuses to start
        preload("java.util.concurrent.ConcurrentHashMap$ForwardingNode", "java.util.concurrent.ConcurrentHashMap$ReservationNode",
                "matlabmaster.multiplayer.agent.ClassPatcher", "matlabmaster.multiplayer.agent.ClassPatcher$Target",
                "matlabmaster.multiplayer.agent.SaveLoader", "matlabmaster.multiplayer.agent.SecureSockets",
                "matlabmaster.multiplayer.agent.SecureSockets$PinningTrust");
        System.getProperties().put(NearestPlayer.ACTIVE_KEY, Boolean.TRUE);
        //loading a save from the title screen without clicking through it (the server instance: see SaveLoader)
        System.getProperties().put(SaveLoader.LOAD_KEY, new SaveLoader());
        //the encrypted multiplayer connection (the mod can't do TLS itself: see SecureSockets)
        SecureSockets.install();
        Transformer transformer = new Transformer(classes);
        transformer.transform(null, "", null, null, new byte[0]); //runs once outside class loading, so it's all linked
        inst.addTransformer(transformer);
        System.out.println("[multiplayer agent] fleets spawn around every player: patching " + classes.size() + " fleet manager classes as they load");
    }

    private static void preload(String... names) {
        for (String name : names) {
            try {
                Class.forName(name, false, NearestPlayerAgent.class.getClassLoader());
            } catch (Throwable t) {
                System.out.println("[multiplayer agent] couldn't preload " + name + ": " + t);
            }
        }
    }

    public static ClassPatcher patcher() {
        ClassPatcher p = new ClassPatcher();
        p.redirect(MISC, "getDistanceLY", "(Lorg/lwjgl/util/vector/Vector2f;Lorg/lwjgl/util/vector/Vector2f;)F", HELPER);
        p.redirect(MISC, "getDistanceLY", "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;Lcom/fs/starfarer/api/campaign/SectorEntityToken;)F", HELPER);
        p.redirect(MISC, "getDistance", "(Lorg/lwjgl/util/vector/Vector2f;Lorg/lwjgl/util/vector/Vector2f;)F", HELPER);
        return p;
    }

    /**
     * The campaign engine: its location calls in advance() go to FullRateLocations, so the locations players are in
     * run every frame, not in one-second steps. Only when the class has exactly the calls this was written for
     * (game version 0.98a); anything else is left as vanilla, since half of them redirected would run locations twice.
     */
    static byte[] patchEngine(byte[] bytes) {
        try {
            ClassPatcher p = enginePatcher();
            byte[] patched = p.patch(bytes);
            if (patched == null || p.patchedCalls() != ENGINE_CALLS) {
                System.out.println("[multiplayer agent] " + ENGINE + ": found " + p.patchedCalls() + " of the " + ENGINE_CALLS
                        + " location calls expected (another game version?), left as vanilla: locations away from the server's own fleet run in one-second steps");
                return null;
            }
            System.out.println("[multiplayer agent] " + ENGINE + ": locations with a player in them run every frame (" + ENGINE_CALLS + " calls)");
            return patched;
        } catch (Throwable t) {
            System.out.println("[multiplayer agent] couldn't patch " + ENGINE + ", left as vanilla: " + t);
            return null;
        }
    }

    public static ClassPatcher enginePatcher() {
        ClassPatcher p = new ClassPatcher();
        for (String owner : new String[]{"com/fs/starfarer/campaign/BaseLocation", "com/fs/starfarer/campaign/Hyperspace"}) {
            p.redirectVirtual(owner, "advanceEvenIfPaused", "(FLcom/fs/starfarer/util/A/new;)V", FULL_RATE, "(Ljava/lang/Object;FLjava/lang/Object;)V");
            p.redirectVirtual(owner, "advance", "(FLcom/fs/starfarer/util/A/new;)V", FULL_RATE, "(Ljava/lang/Object;FLjava/lang/Object;)V");
            p.redirectVirtual(owner, "setActiveThisFrame", "(Z)V", FULL_RATE, "(Ljava/lang/Object;Z)V");
        }
        return p;
    }

    static class Transformer implements ClassFileTransformer {
        private final Set<String> classes;

        Transformer(Set<String> classes) {
            this.classes = classes;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
            if (ENGINE.equals(className)) return patchEngine(bytes);
            if (className == null || !classes.contains(className)) return null;
            try {
                ClassPatcher p = patcher();
                byte[] patched = p.patch(bytes);
                System.out.println("[multiplayer agent] " + className + ": " + p.patchedCalls() + " distance calls now measure to the nearest player");
                return patched;
            } catch (Throwable t) {
                System.out.println("[multiplayer agent] couldn't patch " + className + ", left as vanilla: " + t);
                return null; //never break the game: the class loads unpatched
            }
        }
    }
}
