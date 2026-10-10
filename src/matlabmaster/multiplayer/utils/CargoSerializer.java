package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.campaign.*;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;

public class CargoSerializer {

    public static JSONObject serializeCargoToMap(List<CargoStackAPI> cargoStackList) throws JSONException {
        JSONObject cargoMap = new JSONObject();
        for (CargoStackAPI cargo : cargoStackList) {
            JSONObject cargoObject = new JSONObject();
            float size = cargo.getSize();
            cargoObject.put("quantity", size);
            cargoObject.put("type", cargo.getType().toString());

            String key = "";
            if (cargo.isCommodityStack()) {
                key = cargo.getCommodityId();
                cargoObject.put("commodityId", key);
            } else if (cargo.isWeaponStack()) {
                key = cargo.getWeaponSpecIfWeapon().getWeaponId();
                cargoObject.put("weaponId", key);
            } else if (cargo.isSpecialStack()) {
                key = cargo.getSpecialDataIfSpecial().getId() + "_" + cargo.getSpecialDataIfSpecial().getData();
                cargoObject.put("specialId", cargo.getSpecialDataIfSpecial().getId());
                cargoObject.put("specialData", cargo.getSpecialDataIfSpecial().getData());
            } else if (cargo.isFighterWingStack()) {
                key = cargo.getFighterWingSpecIfWing().getId();
                cargoObject.put("fighterId", key);
            }

            // If key is empty (shouldn't happen), fallback to hash
            if (key.isEmpty()) key = "unknown_" + cargo.hashCode();
            cargoMap.put(key, cargoObject);
        }
        return cargoMap;
    }

    public static void addCargoFromMappedSerialized(JSONObject serializedCargo, CargoAPI cargo) throws JSONException {
        Iterator<?> keys = serializedCargo.keys();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            JSONObject data = serializedCargo.getJSONObject(key);
            int qty = data.getInt("quantity");
            String type = data.getString("type");

            if (type.equals("RESOURCES")) {
                cargo.addCommodity(data.getString("commodityId"), qty);
            } else if (type.equals("WEAPONS")) {
                cargo.addWeapons(data.getString("weaponId"), qty);
            } else if (type.equals("FIGHTER_CHIP")) {
                cargo.addFighters(data.getString("fighterId"), qty);
            } else if (type.equals("SPECIAL")) {
                SpecialItemData spec = new SpecialItemData(data.getString("specialId"), data.optString("specialData", null));
                cargo.addSpecial(spec, qty);
            }
        }
    }

    public static void patchCargo(CargoAPI cargo, JSONObject diff) throws JSONException {
        Iterator<?> keys = diff.keys();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            Object delta = diff.get(key);

            if (delta instanceof JSONObject obj && obj.has("action")) {
                String action = obj.getString("action");

                // For REMOVED, we just clear the stack
                if ("REMOVED".equals(action)) {
                    removeStackByKey(cargo, key);
                    continue;
                }

                // For ADDED or UPDATE, we need the inner data
                JSONObject itemData = obj.getJSONObject("value");
                float targetQty = (float) itemData.getDouble("quantity");
                String type = itemData.getString("type");

                syncItemStack(cargo, key, itemData, type, targetQty);
            } else if (delta instanceof JSONObject nested && nested.optJSONObject("quantity") != null) {
                //a stack on both sides whose quantity changed: the diff went into it, so only that field is there
                setStackSize(cargo, key, (float) nested.getJSONObject("quantity").getDouble("value"));
            }
        }
    }

    private static void syncItemStack(CargoAPI cargo, String key, JSONObject data, String type, float targetQty) throws JSONException {
        float currentQty = getQuantityByKey(cargo, key, data, type);
        float diff = targetQty - currentQty;

        if (diff == 0) return;

        if (type.equals("RESOURCES")) {
            if (diff > 0) cargo.addCommodity(data.getString("commodityId"), diff);
            else cargo.removeCommodity(data.getString("commodityId"), -diff);
        } else if (type.equals("WEAPONS")) {
            if (diff > 0) cargo.addWeapons(data.getString("weaponId"), (int)diff);
            else cargo.removeWeapons(data.getString("weaponId"), (int)-diff);
        } else if (type.equals("FIGHTER_CHIP")) {
            if (diff > 0) cargo.addFighters(data.getString("fighterId"), (int)diff);
            else cargo.removeFighters(data.getString("fighterId"), (int)-diff);
        } else if (type.equals("SPECIAL")) {
            SpecialItemData spec = new SpecialItemData(data.getString("specialId"), data.optString("specialData", null));
            if (diff > 0) cargo.addSpecial(spec, diff);
            else cargo.removeItems(CargoAPI.CargoItemType.SPECIAL, spec, -diff);
        }
    }

    private static float getQuantityByKey(CargoAPI cargo, String key, JSONObject data, String type) throws JSONException {
        if (type.equals("RESOURCES")) return cargo.getCommodityQuantity(key);
        if (type.equals("WEAPONS")) return cargo.getQuantity(CargoAPI.CargoItemType.WEAPONS, key);
        if (type.equals("FIGHTER_CHIP")) return cargo.getQuantity(CargoAPI.CargoItemType.FIGHTER_CHIP, key);
        // Special items need exact match: their stacks' data is a SpecialItemData, never equal to the key's string
        SpecialItemData spec = new SpecialItemData(data.getString("specialId"), data.optString("specialData", null));
        return cargo.getQuantity(CargoAPI.CargoItemType.SPECIAL, spec);
    }

    /** The stack's key in serializeCargoToMap's map. */
    private static String stackKey(CargoStackAPI stack) {
        if (stack.isCommodityStack()) return stack.getCommodityId();
        if (stack.isWeaponStack()) return stack.getWeaponSpecIfWeapon().getWeaponId();
        if (stack.isFighterWingStack()) return stack.getFighterWingSpecIfWing().getId();
        if (stack.isSpecialStack()) return stack.getSpecialDataIfSpecial().getId() + "_" + stack.getSpecialDataIfSpecial().getData();
        return "";
    }

    private static void removeStackByKey(CargoAPI cargo, String key) {
        setStackSize(cargo, key, 0);
    }

    /** The stack of this key gets this size (0 removes it); any other stack of the same key goes (the map has one per key). */
    private static void setStackSize(CargoAPI cargo, String key, float size) {
        for (CargoStackAPI stack : cargo.getStacksCopy()) {
            if (key.equals(stackKey(stack))) {
                stack.setSize(size);
                size = 0;
            }
        }
        cargo.removeEmptyStacks();
    }

    public static void clearCargo(CargoAPI cargo) {
        List<CargoStackAPI> cargoStacks = cargo.getStacksCopy();
        for (CargoStackAPI cargoStack : cargoStacks) {
            cargoStack.setSize(0);
        }
        cargo.removeEmptyStacks();
    }
}
