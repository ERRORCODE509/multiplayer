package matlabmaster.multiplayer.utils;

import matlabmaster.multiplayer.MultiplayerLog;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class JsonDiffUtility {

    public static JSONObject getDifferences(JSONObject oldState, JSONObject newState) {
        JSONObject diffs = new JSONObject();
        compareObjects(oldState, newState, diffs);
        return diffs;
    }

    private static void compareObjects(JSONObject obj1, JSONObject obj2, JSONObject diffs) {
        if (obj1 == null || obj2 == null) return;

        // 1. Check removals and updates
        Iterator<?> keys = obj1.keys();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            Object v1 = obj1.opt(key);

            if (!obj2.has(key)) {
                safePut(diffs, key, createInstruction("REMOVED", null, v1));
            } else {
                Object v2 = obj2.opt(key);
                handleValueComparison(v1, v2, key, diffs);
            }
        }

        // 2. Check for additions
        Iterator<?> keys2 = obj2.keys();
        while (keys2.hasNext()) {
            String key = (String) keys2.next();
            if (!obj1.has(key)) {
                safePut(diffs, key, createInstruction("ADDED", obj2.opt(key), null));
            }
        }
    }

    private static void handleValueComparison(Object v1, Object v2, String key, JSONObject diffs) {
        v1 = (v1 == JSONObject.NULL) ? null : v1;
        v2 = (v2 == JSONObject.NULL) ? null : v2;

        if (v1 == null && v2 == null) return;

        if (v1 instanceof JSONObject o1 && v2 instanceof JSONObject o2) {
            JSONObject subDiff = new JSONObject();
            compareObjects(o1, o2, subDiff);
            if (subDiff.length() > 0) {
                safePut(diffs, key, subDiff);
            }
        } else if (v1 instanceof JSONArray a1 && v2 instanceof JSONArray a2) {
            JSONObject arrayDiffs = new JSONObject();
            compareArrays(a1, a2, arrayDiffs);
            if (arrayDiffs.length() > 0) {
                safePut(diffs, key, arrayDiffs);
            }
        } else if (v1 == null || !v1.equals(v2)) {
            safePut(diffs, key, createInstruction("UPDATE", v2, v1));
        }
    }

    private static void compareArrays(JSONArray arr1, JSONArray arr2, JSONObject arrayDiffs) {
        int max = Math.max(arr1.length(), arr2.length());
        for (int i = 0; i < max; i++) {
            Object v1 = arr1.opt(i);
            Object v2 = arr2.opt(i);
            String indexKey = "[" + i + "]";

            // Past either end, decide by the lengths rather than by the values: an element can be
            // JSONObject.NULL (or a Java null), and comparing those as values would say "no change"
            // although the array shrank or grew by that element.
            if (i >= arr2.length()) {
                safePut(arrayDiffs, indexKey, createInstruction("REMOVED", null, v1));
            } else if (i >= arr1.length()) {
                safePut(arrayDiffs, indexKey, createInstruction("UPDATE", v2, null));
            } else {
                handleValueComparison(v1, v2, indexKey, arrayDiffs);
            }
        }
    }

    /**
     * Applies a getDifferences(old, new) diff to target, a copy of old, so that target then holds the
     * same data as new. The diff may have travelled as text (it is parsed back the same way).
     * Throws when the diff does not fit target (a nested diff for a key target lacks, a bad array
     * index), which means the two sides are out of step and the caller should resend in full.
     * Target may be left half-patched then, so the caller must not keep using it as is.
     * Values are copied in, never shared, so later patches of target cannot change the diff or
     * the object it was taken from.
     * Not for fleet diffs that went through FleetSerializer.replaceWeaponGroupsDiffsWithFullState:
     * the full weapon group map put there is not a diff and would be merged wrongly.
     */
    public static void apply(JSONObject target, JSONObject diff) throws JSONException {
        Iterator<?> keys = diff.keys();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            JSONObject change = diff.optJSONObject(key);
            if (change == null) throw new JSONException("Diff entry \"" + key + "\" is not an object");
            if (isInstruction(change)) {
                if ("REMOVED".equals(change.getString("action"))) {
                    target.remove(key);
                } else {
                    // put(key, NULL) keeps the key with a null value, unlike put(key, null) which removes it.
                    target.put(key, copyValue(change.opt("value")));
                }
                continue;
            }
            Object current = target.opt(key);
            if (current instanceof JSONObject object) {
                apply(object, change);
            } else if (current instanceof JSONArray array) {
                applyToArray(array, change);
            } else {
                throw new JSONException("Diff changes inside \"" + key + "\", which the target has no object or array for");
            }
        }
    }

    /** An array's diff: "[i]" keys. Removals only ever come from the end, so they go last, highest index first. */
    private static void applyToArray(JSONArray target, JSONObject diff) throws JSONException {
        List<Integer> removed = new ArrayList<>();
        Iterator<?> keys = diff.keys();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            int index = arrayIndex(key);
            JSONObject change = diff.optJSONObject(key);
            if (change == null) throw new JSONException("Diff entry \"" + key + "\" is not an object");
            if (isInstruction(change)) {
                if ("REMOVED".equals(change.getString("action"))) {
                    removed.add(index);
                } else {
                    // Past the end, put() pads with NULL first: the indices between are set by their own entries.
                    target.put(index, copyValue(change.opt("value")));
                }
                continue;
            }
            Object current = target.opt(index);
            if (current instanceof JSONObject object) {
                apply(object, change);
            } else if (current instanceof JSONArray array) {
                applyToArray(array, change);
            } else {
                throw new JSONException("Diff changes inside element " + key + ", which the target has no object or array for");
            }
        }
        removed.sort(Collections.reverseOrder());
        for (int index : removed) {
            if (index < target.length()) target.remove(index);
        }
    }

    private static int arrayIndex(String key) throws JSONException {
        if (key.length() > 2 && key.charAt(0) == '[' && key.charAt(key.length() - 1) == ']') {
            try {
                int index = Integer.parseInt(key.substring(1, key.length() - 1));
                if (index >= 0) return index;
            } catch (NumberFormatException ignored) { }
        }
        throw new JSONException("Bad array index in diff: " + key);
    }

    /**
     * Whether a diff entry is an instruction rather than a nested diff. A nested diff's values are
     * always objects (instructions or deeper diffs), so one never has a String "action" even when
     * the data it changes has a field called "action": that field's change is itself an object.
     */
    private static boolean isInstruction(JSONObject change) {
        Object action = change.opt("action");
        return action instanceof String
                && ("ADDED".equals(action) || "REMOVED".equals(action) || "UPDATE".equals(action))
                && change.has("value") && change.has("oldValue");
    }

    /** A deep copy of a JSON value, so nothing is shared between the diff and the patched object. */
    private static Object copyValue(Object value) throws JSONException {
        if (value == null) return JSONObject.NULL;
        if (value instanceof JSONObject object) {
            JSONObject copy = new JSONObject();
            Iterator<?> keys = object.keys();
            while (keys.hasNext()) {
                String key = (String) keys.next();
                copy.put(key, copyValue(object.opt(key)));
            }
            return copy;
        }
        if (value instanceof JSONArray array) {
            JSONArray copy = new JSONArray();
            for (int i = 0; i < array.length(); i++) copy.put(copyValue(array.opt(i)));
            return copy;
        }
        return value; //strings, numbers, booleans and NULL are immutable
    }

    /** An "ADDED" / "REMOVED" / "UPDATE" instruction in the same format getDifferences() produces. */
    public static JSONObject instruction(String action, Object newValue, Object oldValue) {
        return createInstruction(action, newValue, oldValue);
    }

    private static JSONObject createInstruction(String action, Object newValue, Object oldValue) {
        JSONObject instruction = new JSONObject();
        // Since we are creating a fresh object here, safePut ensures no crashes
        // if keys are null or values are unsupported.
        safePut(instruction, "action", action);
        safePut(instruction, "value", newValue == null ? JSONObject.NULL : newValue);
        safePut(instruction, "oldValue", oldValue == null ? JSONObject.NULL : oldValue);
        return instruction;
    }

    /**
     * Internal helper to handle the checked JSONException from .put()
     * This is vital for restrictive sandboxes where you can't allow crashes.
     */
    private static void safePut(JSONObject obj, String key, Object value) {
        try {
            if (key != null) {
                obj.put(key, value);
            }
        } catch (JSONException e) {
            // We log but keep the sync flowing.
            MultiplayerLog.log().error("JSON Sync Error: " + e.getMessage(), e);
        }
    }
}