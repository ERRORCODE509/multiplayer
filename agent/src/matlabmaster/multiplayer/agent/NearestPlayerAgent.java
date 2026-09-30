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
 * player. Nothing else is touched, and nothing on disk changes.
 *
 * Option: -javaagent:MultiplayerAgent.jar=classes=a/b/C;d/e/F replaces the list of classes to patch (internal
 * names), e.g. to add another mod's fleet manager.
 */
public class NearestPlayerAgent {
    static final String MISC = "com/fs/starfarer/api/util/Misc";
    static final String HELPER = "matlabmaster/multiplayer/agent/NearestPlayer";
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
        inst.addTransformer(new Transformer(classes));
        System.getProperties().put(NearestPlayer.ACTIVE_KEY, Boolean.TRUE);
        System.out.println("[multiplayer agent] fleets spawn around every player: patching " + classes.size() + " fleet manager classes as they load");
    }

    public static ClassPatcher patcher() {
        ClassPatcher p = new ClassPatcher();
        p.redirect(MISC, "getDistanceLY", "(Lorg/lwjgl/util/vector/Vector2f;Lorg/lwjgl/util/vector/Vector2f;)F", HELPER);
        p.redirect(MISC, "getDistanceLY", "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;Lcom/fs/starfarer/api/campaign/SectorEntityToken;)F", HELPER);
        p.redirect(MISC, "getDistance", "(Lorg/lwjgl/util/vector/Vector2f;Lorg/lwjgl/util/vector/Vector2f;)F", HELPER);
        return p;
    }

    static class Transformer implements ClassFileTransformer {
        private final Set<String> classes;

        Transformer(Set<String> classes) {
            this.classes = classes;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
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
