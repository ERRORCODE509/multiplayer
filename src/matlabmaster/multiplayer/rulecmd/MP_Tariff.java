package matlabmaster.multiplayer.rulecmd;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemKeys;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.util.Misc;

import java.util.List;
import java.util.Map;

/**
 * A player sets their colony's tariff (data/campaign/rules.csv): the tariff visitors pay there, other players
 * included, whose part is the owner's income (see ClientMarkets.colonyTrade). Kept as a modifier on the market's
 * tariff, so it's saved with the colony and the world's copy of it shows it too.
 *
 *   MP_Tariff menu      the rates to choose from
 *   MP_Tariff isOption  (condition) whether the option picked is one of them
 *   MP_Tariff set       applies the one picked
 */
public class MP_Tariff extends BaseCommandPlugin {
    private static final String MOD = "mp_owner_tariff";
    private static final String PREFIX = "mp_tariff_";
    private static final int[] RATES = {0, 5, 10, 15, 20, 25, 30, 40, 50};

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Misc.Token> params, Map<String, MemoryAPI> memoryMap) {
        if (dialog == null || params.isEmpty()) return false;
        String what = params.get(0).getString(memoryMap);
        MemoryAPI local = memoryMap.get(MemKeys.LOCAL);
        String option = local == null ? null : local.getString("$option");
        MarketAPI market = dialog.getInteractionTarget() == null ? null : dialog.getInteractionTarget().getMarket();
        switch (what) {
            case "isOption":
                return option != null && option.startsWith(PREFIX);
            case "menu": {
                if (market == null) return false;
                dialog.getOptionPanel().clearOptions();
                int current = Math.round(market.getTariff().getModifiedValue() * 100f);
                dialog.getTextPanel().addPara("The tariff at " + market.getName() + " is " + current + "%. Visitors pay it on what they buy and sell here; what other players pay is yours, with the colony's income at the end of the month.");
                for (int rate : RATES) dialog.getOptionPanel().addOption(rate + "%" + (rate == current ? " (current)" : ""), PREFIX + rate);
                dialog.getOptionPanel().addOption("Leave it as it is", PREFIX + "back");
                return true;
            }
            case "set": {
                if (market == null || option == null || !option.startsWith(PREFIX)) return false;
                String rate = option.substring(PREFIX.length());
                if (rate.equals("back")) return true;
                float wanted = Integer.parseInt(rate) / 100f;
                market.getTariff().unmodifyFlat(MOD);
                float without = market.getTariff().getModifiedValue();
                market.getTariff().modifyFlat(MOD, wanted - without, "Set by the colony's owner");
                dialog.getTextPanel().addPara("The tariff at " + market.getName() + " is now " + rate + "%.");
                return true;
            }
            default:
                return false;
        }
    }
}
