package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.*;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.submarkets.StoragePlugin;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.util.Misc;
import matlabmaster.multiplayer.MultiplayerLog;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A player's personal state as JSON (character, officers, idle admins, fleet, cargo and credits, reputation,
 * blueprints, storage at markets, where the fleet is): read from this game to be held by the server, and written back
 * over this game's own when the player logs in, so the server's copy is the one that counts. Sets are JSON objects
 * {id: true} and lists are maps keyed by stable ids, so the diffs sent every second stay small.
 * Colonies (their economy, and the admins governing them) are not part of it: they stay in the owner's game. Their
 * storage is, as any market's: it holds ships and items like the cargo does.
 */
public class PlayerRecord {
    public static final int VERSION = 1;

    /**
     * What the last applied record holds that this game can't place yet: storage at markets it doesn't have (a world
     * market's copy is only made after the record is applied, once the server lists it) and reputation with factions
     * it doesn't have. Every read puts them back, so neither the read sent once the record is applied nor any diff
     * after it drops them from the server's copy; a copy made later gets its storage from here (placeStorage).
     */
    private static final Map<String, JSONObject> carriedStorage = new ConcurrentHashMap<>();
    private static final Map<String, Double> carriedReputation = new ConcurrentHashMap<>();
    /** {the record's commission, this game's when the record was applied} when they differed (see applyReputation). */
    private static volatile String[] carriedCommission;

    /** Person memory worth keeping: what officers' and AI cores' rules read. Only these, since memory can hold any object. */
    private static final String[] PERSON_MEMORY = {
            Misc.MENTORED, Misc.IS_MERCENARY, Misc.MERCENARY_HIRE_TIMESTAMP, Misc.CAPTAIN_UNREMOVABLE,
            Misc.KEEP_CAPTAIN_ON_SHIP_RECOVERY, MemFlags.OFFICER_MAX_LEVEL, MemFlags.OFFICER_MAX_ELITE_SKILLS,
            MemFlags.OFFICER_SKILL_PICKS_PER_LEVEL, MemFlags.EXCEPTIONAL_SLEEPER_POD_OFFICER,
            AICoreOfficerPlugin.AUTOMATED_POINTS_VALUE, AICoreOfficerPlugin.AUTOMATED_POINTS_MULT};

    // ------------------------------------------------------------------ read

    /** The whole record from this game; the storage section is the given one if any (it's costly), else read now. */
    public static JSONObject read(JSONObject storage) throws JSONException {
        SectorAPI sector = Global.getSector();
        CampaignFleetAPI fleet = sector.getPlayerFleet();
        JSONObject record = new JSONObject();
        record.put("version", VERSION);
        record.put("character", readCharacter());

        //the persons on ships that aren't the fleet's officers (a mod's special captain) are kept with the officers,
        //marked, so the captain can still name them by id
        Map<String, String> captainOf = new HashMap<>();
        for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
            PersonAPI captain = member.getCaptain();
            if (captain != null && !captain.isDefault()) captainOf.put(captain.getId(), member.getId());
        }
        PersonAPI player = sector.getPlayerPerson();
        JSONObject officers = new JSONObject();
        Set<String> officerIds = new HashSet<>();
        for (OfficerDataAPI officer : fleet.getFleetData().getOfficersCopy()) {
            PersonAPI person = officer.getPerson();
            if (person == null || person == player) continue;
            JSONObject o = readPerson(person);
            if (captainOf.containsKey(person.getId())) o.put("member", captainOf.get(person.getId()));
            officers.put(person.getId(), o);
            officerIds.add(person.getId());
        }
        JSONObject ships = new JSONObject();
        int order = 0;
        for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
            JSONObject m = readMember(member, order++);
            PersonAPI captain = member.getCaptain();
            if (captain == null || captain.isDefault()) {
                //no officer: nothing to write
            } else if (captain == player || member.isFlagship()) {
                m.put("captain", "player");
            } else if (officerIds.contains(captain.getId())) {
                m.put("captain", captain.getId());
            } else if (captain.isAICore()) {
                m.put("captain", readAICore(captain));
            } else {
                JSONObject o = readPerson(captain);
                o.put("member", member.getId());
                o.put("notOfficer", true);
                officers.put(captain.getId(), o);
                m.put("captain", captain.getId());
            }
            ships.put(member.getId(), m);
        }
        record.put("officers", officers);
        record.put("fleet", ships);

        JSONObject admins = new JSONObject();
        for (AdminData admin : sector.getCharacterData().getAdmins()) {
            //an admin governing a colony is the colony's, which stays in this game
            if (admin.getPerson() == null || admin.getMarket() != null) continue;
            admins.put(admin.getPerson().getId(), readPerson(admin.getPerson()));
        }
        record.put("admins", admins);

        JSONObject cargo = new JSONObject();
        cargo.put("credits", num(fleet.getCargo().getCredits().get()));
        cargo.put("stacks", readStacks(fleet.getCargo()));
        record.put("cargo", cargo);

        FactionAPI playerFaction = sector.getPlayerFaction();
        JSONObject reputation = new JSONObject();
        for (FactionAPI faction : sector.getAllFactions()) {
            String id = faction.getId();
            if (id.equals(Factions.PLAYER) || PlayerFactions.isSlot(id)) continue;
            //the full float: a rounded value can cross a reputation level's boundary
            reputation.put(id, num(playerFaction.getRelationship(id)));
        }
        for (Iterator<Map.Entry<String, Double>> it = carriedReputation.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Double> entry = it.next();
            //a faction that turned up since is read from the game like the others
            if (sector.getFaction(entry.getKey()) != null) it.remove();
            else reputation.put(entry.getKey(), num(entry.getValue()));
        }
        record.put("reputation", reputation);
        String commission = Misc.getCommissionFactionId();
        String[] carried = carriedCommission;
        if (carried != null) {
            //the record's stands until the commission changes here: then the player chose it in this session
            if (Objects.equals(commission, carried[1])) commission = carried[0];
            else carriedCommission = null;
        }
        if (commission != null) record.put("commission", commission);

        JSONObject blueprints = new JSONObject();
        blueprints.put("ships", set(playerFaction.getKnownShips()));
        blueprints.put("weapons", set(playerFaction.getKnownWeapons()));
        blueprints.put("fighters", set(playerFaction.getKnownFighters()));
        blueprints.put("hullmods", set(playerFaction.getKnownHullMods()));
        blueprints.put("industries", set(playerFaction.getKnownIndustries()));
        record.put("blueprints", blueprints);

        record.put("storage", storage != null ? withCarried(storage) : readStorage());

        JSONObject location = new JSONObject();
        LocationAPI where = fleet.getContainingLocation();
        location.put("id", where == null || where.isHyperspace() ? "hyperspace" : where.getId());
        location.put("x", Math.round(fleet.getLocation().x));
        location.put("y", Math.round(fleet.getLocation().y));
        record.put("location", location);
        return record;
    }

    /**
     * The storage section alone: every market's storage (the player's own colonies' too) that holds anything, and
     * the carried entries of markets this game doesn't have.
     */
    public static JSONObject readStorage() throws JSONException {
        JSONObject storage = new JSONObject();
        Map<String, MarketAPI> markets = storageMarkets();
        for (MarketAPI market : markets.values()) {
            JSONObject entry = readMarketStorage(market);
            if (entry != null) storage.put(market.getId(), entry);
        }
        for (Iterator<Map.Entry<String, JSONObject>> it = carriedStorage.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, JSONObject> carried = it.next();
            MarketAPI market = markets.get(carried.getKey());
            if (market == null) {
                storage.put(carried.getKey(), carried.getValue());
                continue;
            }
            //the market turned up without placeStorage: an empty storage takes the record's, one the player has
            //used since is theirs (and the record's entry can't be told apart from it any more)
            it.remove();
            if (storage.has(market.getId())) {
                MultiplayerLog.log().warn("Storage at market " + market.getId() + " was used before the record's could be put there: the record's is dropped");
                continue;
            }
            fillCarried(market, carried.getValue());
            JSONObject entry = readMarketStorage(market);
            if (entry != null) storage.put(market.getId(), entry);
        }
        return storage;
    }

    /** One market's storage as the storage section has it, or null when it has none or it holds nothing. */
    public static JSONObject readMarketStorage(MarketAPI market) throws JSONException {
        CargoAPI cargo = Misc.getStorageCargo(market);
        if (cargo == null) return null;
        JSONObject stacks = readStacks(cargo);
        JSONObject ships = new JSONObject();
        FleetDataAPI mothballed = cargo.getMothballedShips();
        if (mothballed != null) {
            int order = 0;
            for (FleetMemberAPI member : mothballed.getMembersListCopy()) {
                JSONObject m = readMember(member, order++);
                PersonAPI captain = member.getCaptain();
                if (captain != null && !captain.isDefault() && captain.isAICore()) m.put("captain", readAICore(captain));
                ships.put(member.getId(), m);
            }
        }
        if (stacks.length() == 0 && ships.length() == 0) return null;
        JSONObject entry = new JSONObject();
        entry.put("stacks", stacks);
        entry.put("ships", ships);
        return entry;
    }

    /**
     * A storage section read earlier, with the carried entries it lacks. A copy when anything is added: the given
     * one can be the section of the record last sent, which the next diff is made against.
     */
    private static JSONObject withCarried(JSONObject storage) throws JSONException {
        JSONObject merged = null;
        for (Map.Entry<String, JSONObject> carried : carriedStorage.entrySet()) {
            if (storage.has(carried.getKey())) continue;
            if (merged == null) {
                merged = new JSONObject();
                for (String id : keys(storage)) merged.put(id, storage.get(id));
            }
            merged.put(carried.getKey(), carried.getValue());
        }
        return merged != null ? merged : storage;
    }

    /**
     * Called as a market this game didn't have is made (a world market's copy): its storage is filled from the
     * applied record's entry for it, if one was carried. A market without a storage submarket keeps it carried.
     */
    public static void placeStorage(MarketAPI market) {
        if (market == null || market.getSubmarket(Submarkets.SUBMARKET_STORAGE) == null) return;
        JSONObject entry = carriedStorage.remove(market.getId());
        if (entry != null) fillCarried(market, entry);
    }

    /** Drops everything carried from the last applied record: the session it belonged to is over. */
    public static void forgetCarried() {
        carriedStorage.clear();
        carriedReputation.clear();
        carriedCommission = null;
    }

    private static void fillCarried(MarketAPI market, JSONObject entry) {
        List<String> problems = new ArrayList<>();
        try {
            fillStorage(market, entry, problems);
        } catch (Exception e) {
            MultiplayerLog.log().error("Putting the player record's storage at market " + market.getId() + " failed", e);
            return;
        }
        MultiplayerLog.log().info("Put the player record's storage at market " + market.getId() + " there"
                + (problems.isEmpty() ? "" : ", except: " + String.join("; ", problems)));
    }

    private static JSONObject readCharacter() throws JSONException {
        SectorAPI sector = Global.getSector();
        PersonAPI person = sector.getPlayerPerson();
        MutableCharacterStatsAPI stats = sector.getPlayerStats();
        CharacterDataAPI data = sector.getCharacterData();
        JSONObject c = new JSONObject();
        c.put("first", person.getName().getFirst());
        c.put("last", person.getName().getLast());
        c.put("gender", person.getGender().name());
        c.put("portrait", person.getPortraitSprite());
        if (data.getHonorific() != null) c.put("honorific", data.getHonorific());
        c.put("level", stats.getLevel());
        c.put("xp", stats.getXP());
        c.put("bonusXp", stats.getBonusXp());
        c.put("deferredBonusXp", stats.getDeferredBonusXp());
        c.put("points", stats.getPoints());
        c.put("storyPoints", stats.getStoryPoints());
        c.put("skills", readSkills(stats));
        c.put("everElite", set(data.getSkillsEverMadeElite()));
        c.put("hullMods", set(data.getHullMods()));
        c.put("abilities", set(data.getAbilities()));
        return c;
    }

    private static JSONObject readPerson(PersonAPI person) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("first", person.getName().getFirst());
        o.put("last", person.getName().getLast());
        o.put("gender", person.getGender().name());
        o.put("portrait", person.getPortraitSprite());
        if (person.getPersonalityAPI() != null) o.put("personality", person.getPersonalityAPI().getId());
        if (person.getRankId() != null) o.put("rank", person.getRankId());
        if (person.getPostId() != null) o.put("post", person.getPostId());
        o.put("level", person.getStats().getLevel());
        o.put("xp", person.getStats().getXP());
        o.put("skills", readSkills(person.getStats()));
        o.put("memory", readMemory(person));
        return o;
    }

    private static JSONObject readAICore(PersonAPI core) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("aiCore", core.getAICoreId());
        o.put("level", core.getStats().getLevel());
        o.put("skills", readSkills(core.getStats()));
        o.put("memory", readMemory(core));
        return o;
    }

    /** Skill id -> level (1, or 2 for elite); aptitudes aren't skills to restore. */
    private static JSONObject readSkills(MutableCharacterStatsAPI stats) throws JSONException {
        JSONObject skills = new JSONObject();
        for (MutableCharacterStatsAPI.SkillLevelAPI skill : stats.getSkillsCopy()) {
            if (skill.getSkill() == null || skill.getSkill().isAptitudeEffect() || skill.getLevel() <= 0) continue;
            skills.put(skill.getSkill().getId(), level(skill.getLevel()));
        }
        return skills;
    }

    private static JSONObject readMemory(PersonAPI person) throws JSONException {
        JSONObject memory = new JSONObject();
        MemoryAPI mem = person.getMemoryWithoutUpdate();
        for (String key : PERSON_MEMORY) {
            Object value = mem.get(key);
            if (value instanceof Boolean || value instanceof String) {
                memory.put(key, value);
            } else if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
                memory.put(key, ((Number) value).longValue());
            } else if (value instanceof Number) {
                memory.put(key, num(((Number) value).doubleValue()));
            }
        }
        return memory;
    }

    /** A fleet member (or a ship in storage): its variant's fit plus the member's own state, captain aside. */
    private static JSONObject readMember(FleetMemberAPI member, int order) throws JSONException {
        JSONObject m = readVariant(member.getVariant());
        m.put("order", order);
        m.put("shipName", member.getShipName() == null ? "" : member.getShipName());
        m.put("flagship", member.isFlagship());
        m.put("mothballed", member.getRepairTracker().isMothballed());
        m.put("crashMothballed", member.getRepairTracker().isCrashMothballed());
        m.put("crBeforeMothball", num(member.getRepairTracker().getCRPriorToMothballing()));
        m.put("cr", num(member.getRepairTracker().getCR()));
        m.put("hull", num(member.getStatus().getHullFraction()));
        if (member.getPersonalityOverride() != null) m.put("personalityOverride", member.getPersonalityOverride());
        return m;
    }

    private static JSONObject readVariant(ShipVariantAPI variant) throws JSONException {
        JSONObject v = new JSONObject();
        v.put("hullId", variant.getHullSpec().getHullId()); //exact: a D-hull's "_default_D" too
        v.put("variantId", variant.getHullVariantId());
        v.put("displayName", variant.getDisplayName() == null ? "" : variant.getDisplayName());
        v.put("vents", variant.getNumFluxVents());
        v.put("caps", variant.getNumFluxCapacitors());
        //built-ins come with the hull; perma-mods (d-mods...) and s-mods are kept apart, so that each comes back as
        //what it was (a d-mod read as a plain mod could be taken off in the refit screen)
        Set<String> sModdedBuiltIns = new LinkedHashSet<>(variant.getSModdedBuiltIns());
        Set<String> sMods = new LinkedHashSet<>(variant.getSMods());
        sMods.removeAll(sModdedBuiltIns);
        Set<String> perma = new LinkedHashSet<>(variant.getPermaMods());
        perma.removeAll(sMods);
        perma.removeAll(sModdedBuiltIns);
        Set<String> mods = new LinkedHashSet<>(variant.getNonBuiltInHullmods());
        mods.removeAll(variant.getPermaMods());
        mods.removeAll(sMods);
        mods.removeAll(sModdedBuiltIns);
        v.put("mods", set(mods));
        v.put("permaMods", set(perma));
        v.put("sMods", set(sMods));
        v.put("sModdedBuiltIns", set(sModdedBuiltIns));
        v.put("suppressed", set(variant.getSuppressedMods()));
        JSONObject weapons = new JSONObject();
        for (String slot : variant.getNonBuiltInWeaponSlots()) {
            String weapon = variant.getWeaponId(slot);
            if (weapon != null) weapons.put(slot, weapon);
        }
        v.put("weapons", weapons);
        JSONObject wings = new JSONObject();
        int bays = Global.getSettings().computeNumFighterBays(variant);
        for (int i = 0; i < bays; i++) {
            if (variant.getHullSpec().isBuiltInWing(i)) continue;
            String wing = variant.getWingId(i);
            if (wing != null && !wing.isEmpty()) wings.put(String.valueOf(i), wing);
        }
        v.put("wings", wings);
        JSONObject groups = new JSONObject();
        List<WeaponGroupSpec> specs = variant.getWeaponGroups();
        for (int i = 0; i < specs.size(); i++) {
            WeaponGroupSpec spec = specs.get(i);
            JSONObject g = new JSONObject();
            g.put("type", spec.getType().name());
            g.put("autofire", spec.isAutofireOnByDefault());
            g.put("slots", set(spec.getSlots()));
            groups.put(String.valueOf(i), g);
        }
        v.put("groups", groups);
        v.put("tags", set(variant.getTags()));
        JSONObject modules = new JSONObject();
        for (String slot : variant.getModuleSlots()) {
            ShipVariantAPI module = variant.getModuleVariant(slot);
            if (module != null) modules.put(slot, readVariant(module));
        }
        v.put("modules", modules);
        return v;
    }

    /** Stacks keyed type:id (:data for special items that have data), quantities exact (fuel and supplies aren't whole). */
    private static JSONObject readStacks(CargoAPI cargo) throws JSONException {
        JSONObject stacks = new JSONObject();
        for (CargoStackAPI stack : cargo.getStacksCopy()) {
            CargoAPI.CargoItemType type = stack.getType();
            if (type == null || type == CargoAPI.CargoItemType.NULL || stack.getSize() <= 0) continue;
            String id;
            String data = null;
            if (type == CargoAPI.CargoItemType.SPECIAL) {
                SpecialItemData special = stack.getSpecialDataIfSpecial();
                if (special == null) continue;
                id = special.getId();
                data = special.getData();
            } else if (stack.getData() instanceof String) {
                id = (String) stack.getData();
            } else {
                continue;
            }
            String key = type.name() + ":" + id + (data != null ? ":" + data : "");
            JSONObject entry = stacks.optJSONObject(key);
            if (entry == null) {
                entry = new JSONObject();
                entry.put("type", type.name());
                entry.put("id", id);
                if (data != null) entry.put("data", data);
                entry.put("qty", num(stack.getSize()));
                stacks.put(key, entry);
            } else {
                entry.put("qty", num(entry.getDouble("qty") + stack.getSize())); //the same item split over two stacks
            }
        }
        return stacks;
    }

    // ------------------------------------------------------------------ apply

    /**
     * Whether the record can be written now: a campaign frame with nothing open over it (an interaction, the
     * fleet/refit/cargo screens, a menu), not jumping, not in a battle. Writing the fleet or the cargo from under one
     * of those would leave it showing (and later saving) objects that aren't the fleet's any more.
     */
    public static boolean canApplyNow() {
        if (Global.getCurrentState() != GameState.CAMPAIGN) return false;
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getPlayerFleet() == null || sector.getCampaignUI() == null) return false;
        CampaignUIAPI ui = sector.getCampaignUI();
        //isShowingDialog covers the core screens too (vanilla's tutorial waits on it for the character tab to close);
        //getCurrentCoreTab isn't used, since nothing says it goes back to null once the tab is closed
        if (ui.getCurrentInteractionDialog() != null || ui.isShowingDialog() || ui.isShowingMenu()) return false;
        CampaignFleetAPI fleet = sector.getPlayerFleet();
        return !fleet.isInHyperspaceTransition() && fleet.getBattle() == null && fleet.getContainingLocation() != null;
    }

    /**
     * Writes the record over this game's personal state (game thread, only when canApplyNow()). Everything that can
     * be applied is, and what can't (an id this game doesn't know, a part that failed) is returned and logged: the
     * read that follows leaves it out, so the server's copy drops it once instead of failing every join. Throws only
     * when the record is unusable (another version, or not one ship of its fleet can be built), before anything of
     * this game's is changed.
     */
    public static List<String> apply(JSONObject record) throws Exception {
        int version = record.optInt("version", VERSION);
        if (version != VERSION) throw new Exception("the record is version " + version + ", this game reads " + VERSION);
        List<String> problems = new ArrayList<>();
        FleetPlan plan = buildFleet(record, problems);
        forgetCarried(); //what this record can't place is carried below
        int[] counts = new int[2]; //ships, officers

        //skills first: the officer count, OP and s-mod limits of the fleet below come from them
        section(problems, "character", () -> applyCharacter(record.optJSONObject("character"), problems));
        section(problems, "blueprints", () -> applyBlueprints(record.optJSONObject("blueprints"), problems));
        section(problems, "abilities", () -> applyAbilities(record.optJSONObject("character"), problems));
        section(problems, "reputation", () -> applyReputation(record, problems));
        section(problems, "fleet", () -> applyFleet(record, plan, problems, counts));
        section(problems, "admins", () -> applyAdmins(record.optJSONObject("admins"), problems));
        section(problems, "cargo", () -> applyCargo(record.optJSONObject("cargo"), problems));
        int[] storageMarkets = new int[1];
        section(problems, "storage", () -> storageMarkets[0] = applyStorage(record.optJSONObject("storage"), problems));
        section(problems, "location", () -> applyLocation(record.optJSONObject("location")));

        MultiplayerLog.log().info("Applied the server's player record: " + counts[0] + " ships, " + counts[1] + " officers, "
                + Math.round(Global.getSector().getPlayerFleet().getCargo().getCredits().get()) + " credits, "
                + storageMarkets[0] + " markets' storage" + (carriedStorage.isEmpty() ? "" : " (" + carriedStorage.size()
                + " more kept for markets this game doesn't have yet)") + (problems.isEmpty() ? "" : ", " + problems.size() + " problems"));
        if (!problems.isEmpty()) {
            MultiplayerLog.log().warn("What of the server's player record couldn't be applied: " + String.join("; ", problems));
        }
        return problems;
    }

    private interface Part {
        void run() throws Exception;
    }

    /** One part of the record; an error in it is a problem, and the other parts are still applied. */
    private static void section(List<String> problems, String name, Part part) {
        try {
            part.run();
        } catch (Exception e) {
            MultiplayerLog.log().error("Applying the player record's " + name + " failed", e);
            problems.add(name + ": " + e);
        }
    }

    private static void applyCharacter(JSONObject c, List<String> problems) throws JSONException {
        if (c == null) return;
        SectorAPI sector = Global.getSector();
        PersonAPI person = sector.getPlayerPerson();
        MutableCharacterStatsAPI stats = sector.getPlayerStats();
        CharacterDataAPI data = sector.getCharacterData();

        //setters only: addXP and levelUpIfNeeded would grant skill and story points on top of the record's
        stats.setSkipRefresh(true);
        try {
            writeSkills(stats, c.optJSONObject("skills"), problems, "the character");
            stats.setLevel(c.getInt("level"));
            stats.setXP(c.getLong("xp"));
            stats.setBonusXp(c.optLong("bonusXp", 0));
            stats.setDeferredBonusXp(c.optLong("deferredBonusXp", 0));
            stats.setPoints(c.getInt("points"));
            stats.setStoryPoints(c.getInt("storyPoints"));
        } finally {
            stats.setSkipRefresh(false);
        }
        stats.refreshCharacterStatsEffects();

        FullName.Gender gender = gender(c.optString("gender", null));
        person.setName(new FullName(c.optString("first", ""), c.optString("last", ""), gender));
        person.setGender(gender);
        data.setName(person.getNameString(), gender);
        String portrait = c.optString("portrait", null);
        if (portrait != null) {
            person.setPortraitSprite(portrait);
            data.setPortraitName(portrait);
        }
        data.setHonorific(c.optString("honorific", null));

        //the game's live sets (no setters): made equal to the record's
        matchSet(data.getSkillsEverMadeElite(), keys(c.optJSONObject("everElite")));
        Set<String> hullMods = keys(c.optJSONObject("hullMods"));
        for (String id : new ArrayList<>(data.getHullMods())) {
            if (!hullMods.contains(id)) data.removeHullMod(id);
        }
        for (String id : hullMods) {
            if (data.knowsHullMod(id)) continue;
            if (!known(() -> Global.getSettings().getHullModSpec(id))) problems.add("unknown learned hullmod " + id);
            else data.addHullMod(id);
        }
        sector.getPlayerFleet().getFleetData().setSyncNeeded();
    }

    private static void applyBlueprints(JSONObject b, List<String> problems) {
        if (b == null) return;
        FactionAPI faction = Global.getSector().getPlayerFaction();
        //added without a timestamp: one only says "learned after this fleet was made", which a restore isn't
        matchKnown(faction.getKnownShips(), keys(b.optJSONObject("ships")), id -> faction.addKnownShip(id, false),
                faction::removeKnownShip, id -> known(() -> Global.getSettings().getHullSpec(id)), "ship", problems);
        matchKnown(faction.getKnownWeapons(), keys(b.optJSONObject("weapons")), id -> faction.addKnownWeapon(id, false),
                faction::removeKnownWeapon, id -> known(() -> Global.getSettings().getWeaponSpec(id)), "weapon", problems);
        matchKnown(faction.getKnownFighters(), keys(b.optJSONObject("fighters")), id -> faction.addKnownFighter(id, false),
                faction::removeKnownFighter, id -> known(() -> Global.getSettings().getFighterWingSpec(id)), "fighter", problems);
        matchKnown(faction.getKnownHullMods(), keys(b.optJSONObject("hullmods")), faction::addKnownHullMod,
                faction::removeKnownHullMod, id -> known(() -> Global.getSettings().getHullModSpec(id)), "hullmod", problems);
        matchKnown(faction.getKnownIndustries(), keys(b.optJSONObject("industries")), faction::addKnownIndustry,
                faction::removeKnownIndustry, id -> known(() -> Global.getSettings().getIndustrySpec(id)), "industry", problems);
    }

    /**
     * Makes a known-blueprints set equal to the record's. An id this game has no spec for is a problem only when it's
     * new here: one the game already lists (a faction file naming a hull no mod provides) came from this game too.
     */
    private static void matchKnown(Set<String> current, Set<String> wanted, java.util.function.Consumer<String> add,
                                   java.util.function.Consumer<String> remove, java.util.function.Predicate<String> exists,
                                   String what, List<String> problems) {
        for (String id : new ArrayList<>(current)) {
            if (!wanted.contains(id)) remove.accept(id);
        }
        for (String id : wanted) {
            if (current.contains(id)) continue;
            if (!exists.test(id)) {
                problems.add("unknown " + what + " blueprint " + id);
                continue;
            }
            add.accept(id);
        }
    }

    /**
     * The character's abilities, and the fleet's to match. Only abilities the record drops from the character are
     * taken off the fleet (unless a skill grants them): a mod may give the fleet abilities of its own. Nothing is
     * activated (a toggle like the transponder keeps its state).
     */
    private static void applyAbilities(JSONObject c, List<String> problems) {
        if (c == null || c.optJSONObject("abilities") == null) return;
        CharacterDataAPI data = Global.getSector().getCharacterData();
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        Set<String> wanted = keys(c.optJSONObject("abilities"));
        Set<String> granted = new HashSet<>(Global.getSector().getPlayerStats().getGrantedAbilityIds());
        for (String id : new ArrayList<>(data.getAbilities())) {
            if (wanted.contains(id)) continue;
            data.removeAbility(id);
            if (!granted.contains(id) && fleet.hasAbility(id)) fleet.removeAbility(id);
        }
        for (String id : wanted) {
            if (!known(() -> Global.getSettings().getAbilitySpec(id))) {
                problems.add("unknown ability " + id);
                continue;
            }
            if (!data.getAbilities().contains(id)) data.addAbility(id);
            if (!fleet.hasAbility(id)) fleet.addAbility(id);
        }
    }

    /**
     * The commission isn't changed here, only checked: vanilla's FactionCommissionIntel undoes and makes reputation
     * changes as it ends and starts (and its script is taken out while connected), and Nexerelin replaces it with its
     * own intel that also saves and syncs the player's relations (Nex_Commission.accept). Starting or ending one from
     * here could leave either half-done, so a different commission is left as it is, and the reputation values below
     * are the record's either way. Reads keep sending the record's commission until this game's changes, so an old
     * save's commission doesn't become the server's.
     */
    private static void applyReputation(JSONObject record, List<String> problems) throws JSONException {
        String commission = record.optString("commission", null);
        String local = Misc.getCommissionFactionId();
        if (!Objects.equals(commission, local)) {
            carriedCommission = new String[]{commission, local};
            problems.add("the record's commission is " + commission + " but this game's is " + local + ": left as it is");
        }
        JSONObject reputation = record.optJSONObject("reputation");
        if (reputation == null) return;
        FactionAPI player = Global.getSector().getPlayerFaction();
        for (Iterator<?> it = reputation.keys(); it.hasNext(); ) {
            String id = (String) it.next();
            if (id.equals(Factions.PLAYER) || PlayerFactions.isSlot(id)) continue;
            if (Global.getSector().getFaction(id) == null) {
                //a faction this game lacks: kept, so that this game's reads don't drop it from the server's copy
                carriedReputation.put(id, reputation.getDouble(id));
                continue;
            }
            player.setRelationship(id, (float) reputation.getDouble(id));
        }
    }

    /** The record's ships, built but not in the fleet yet, with their AI core captains. */
    private static final class FleetPlan {
        final Map<String, FleetMemberAPI> members = new LinkedHashMap<>();
        final Map<FleetMemberAPI, JSONObject> built = new LinkedHashMap<>();
        final Map<FleetMemberAPI, PersonAPI> cores = new HashMap<>();
        FleetMemberAPI flagship;
    }

    /**
     * Builds the record's ships without touching this game's fleet. Throws when it has ships and not one can be
     * built, or has no fleet section: the record is then unusable, and the player must keep the fleet they have
     * rather than none. An empty fleet is a defeat the player hadn't recovered from: it's applied as it is, and
     * vanilla's respawn gives them a new fleet as it would offline (otherwise leaving before the respawn would undo
     * losing every ship). A ship that can't be built is only a problem, as is a record without a flagship (its first
     * ship becomes it).
     */
    private static FleetPlan buildFleet(JSONObject record, List<String> problems) throws Exception {
        JSONObject ships = record.optJSONObject("fleet");
        if (ships == null) throw new Exception("the record has no fleet");
        FleetPlan plan = new FleetPlan();
        if (ships.length() == 0) return plan;
        for (String id : byOrder(ships)) {
            JSONObject m = ships.optJSONObject(id);
            if (m == null) {
                problems.add("ship " + id + ": not an object");
                continue;
            }
            FleetMemberAPI member;
            try {
                member = buildMember(id, m, problems);
            } catch (JSONException | RuntimeException e) {
                MultiplayerLog.log().error("Building ship " + id + " of the player record failed", e);
                problems.add("ship " + id + ": " + e);
                continue;
            }
            if (member == null) continue;
            plan.members.put(id, member);
            plan.built.put(member, m);
            if (m.optBoolean("flagship") && plan.flagship == null) plan.flagship = member;
        }
        if (plan.members.isEmpty()) {
            throw new Exception("not one ship of the record's fleet can be built (" + String.join("; ", problems) + ")");
        }
        if (plan.flagship == null) {
            problems.add("the record has no flagship: its first ship is made the flagship");
            plan.flagship = plan.members.values().iterator().next();
        }
        for (Map.Entry<FleetMemberAPI, JSONObject> entry : plan.built.entrySet()) {
            FleetMemberAPI member = entry.getKey();
            Object captain = entry.getValue().opt("captain");
            if (!(captain instanceof JSONObject) || member == plan.flagship) continue;
            try {
                PersonAPI core = buildAICore((JSONObject) captain, problems, "ship " + member.getId());
                if (core != null) plan.cores.put(member, core);
            } catch (JSONException | RuntimeException e) {
                MultiplayerLog.log().error("Building the AI core of ship " + member.getId() + " of the player record failed", e);
                problems.add("ship " + member.getId() + "'s AI core: " + e);
            }
        }
        return plan;
    }

    /**
     * The fleet, rebuilt in place (the same fleet object keeps its id, location, scripts and memory) from the built
     * plan. The player stays its commander and the flagship's captain: setCommander is never called, so the
     * character is never replaced by a copy. Officers keep their person objects (and pending skill picks) when this
     * game has them.
     */
    private static void applyFleet(JSONObject record, FleetPlan plan, List<String> problems, int[] counts) throws JSONException {
        JSONObject officers = record.optJSONObject("officers");
        if (officers == null) officers = new JSONObject();
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        FleetDataAPI data = fleet.getFleetData();
        PersonAPI player = Global.getSector().getPlayerPerson();

        Map<String, OfficerDataAPI> oldOfficers = new HashMap<>();
        for (OfficerDataAPI officer : data.getOfficersCopy()) {
            if (officer.getPerson() != null) oldOfficers.put(officer.getPerson().getId(), officer);
        }
        Map<String, PersonAPI> oldCaptains = new HashMap<>();
        for (FleetMemberAPI member : data.getMembersListCopy()) {
            PersonAPI captain = member.getCaptain();
            if (captain != null && !captain.isDefault() && captain != player) oldCaptains.put(captain.getId(), captain);
        }

        //the officers written before the old fleet goes; one that can't be written in full is still kept, as it is
        Map<String, PersonAPI> persons = new LinkedHashMap<>();
        for (String personId : keys(officers)) {
            JSONObject o = officers.optJSONObject(personId);
            if (o == null) {
                problems.add("officer " + personId + ": not an object");
                continue;
            }
            OfficerDataAPI old = oldOfficers.get(personId);
            PersonAPI person = old != null ? old.getPerson() : oldCaptains.get(personId);
            if (person == null) {
                person = Global.getFactory().createPerson();
                person.setId(personId);
                person.setFaction(Factions.PLAYER);
            }
            try {
                writePerson(person, o, problems, "officer " + personId);
            } catch (JSONException | RuntimeException e) {
                MultiplayerLog.log().error("Writing officer " + personId + " of the player record failed", e);
                problems.add("officer " + personId + ": " + e);
            }
            persons.put(personId, person);
        }

        //unassigned before the officers go (as vanilla does when an officer leaves), then the ships
        for (FleetMemberAPI member : data.getMembersListCopy()) {
            PersonAPI captain = member.getCaptain();
            if (captain != null && !captain.isDefault() && captain != player) member.setCaptain(Global.getFactory().createPerson());
        }
        for (OfficerDataAPI officer : data.getOfficersCopy()) data.removeOfficer(officer.getPerson());
        for (FleetMemberAPI member : data.getMembersListCopy()) data.removeFleetMember(member);

        List<FleetMemberAPI> added = new ArrayList<>();
        try {
            for (FleetMemberAPI member : plan.members.values()) {
                data.addFleetMember(member);
                added.add(member);
            }
            for (Map.Entry<String, PersonAPI> entry : persons.entrySet()) {
                String personId = entry.getKey();
                PersonAPI person = entry.getValue();
                JSONObject o = officers.getJSONObject(personId);
                if (!o.optBoolean("notOfficer")) {
                    OfficerDataAPI old = oldOfficers.get(personId);
                    if (old != null) data.addOfficer(old);
                    else data.addOfficer(person);
                    counts[1]++;
                }
                String memberId = o.optString("member", null);
                if (memberId == null) continue;
                FleetMemberAPI member = plan.members.get(memberId);
                if (member == null) {
                    problems.add("officer " + personId + " captains the missing ship " + memberId);
                } else if (member != plan.flagship) {
                    member.setCaptain(person);
                }
            }
            for (Map.Entry<FleetMemberAPI, JSONObject> entry : plan.built.entrySet()) {
                FleetMemberAPI member = entry.getKey();
                Object captain = entry.getValue().opt("captain");
                PersonAPI core = plan.cores.get(member);
                if (core != null) {
                    member.setCaptain(core);
                } else if (captain instanceof String && !"player".equals(captain) && !officers.has((String) captain)) {
                    problems.add("ship " + member.getId() + "'s captain " + captain + " isn't among the officers");
                }
            }
        } finally {
            //whatever failed above, the player captains the flagship: setFlagship makes the commander its captain,
            //and nothing else would put the player back on a ship
            if (!added.isEmpty()) {
                FleetMemberAPI flagship = added.contains(plan.flagship) ? plan.flagship : added.get(0);
                flagship.setCaptain(player);
                data.setFlagship(flagship);
                data.ensureHasFlagship();
                data.setSyncNeeded();
                data.syncIfNeeded();
                fleet.forceSync();
                fleet.updateCounts();
                //after the sync: max CR depends on the skills and mods now in place
                for (FleetMemberAPI member : added) {
                    try {
                        writeMemberState(member, plan.built.get(member));
                    } catch (RuntimeException e) {
                        MultiplayerLog.log().error("Writing the state of ship " + member.getId() + " of the player record failed", e);
                        problems.add("ship " + member.getId() + "'s CR and hull: " + e);
                    }
                }
            }
            counts[0] = added.size();
        }
    }

    /** CR, hull and mothball state: only once the member is in its fleet and synced. */
    private static void writeMemberState(FleetMemberAPI member, JSONObject m) {
        member.getRepairTracker().setMothballed(m.optBoolean("mothballed"));
        member.getRepairTracker().setCrashMothballed(m.optBoolean("crashMothballed"));
        member.getRepairTracker().setCRPriorToMothballing((float) m.optDouble("crBeforeMothball", 0));
        member.getRepairTracker().setCR((float) m.optDouble("cr", member.getRepairTracker().getMaxCR()));
        member.getStatus().updateNumStatusesFromMember();
        member.getStatus().setHullFraction((float) m.optDouble("hull", 1));
    }

    /** A member rebuilt from the record, or null (a problem noted) when its hull isn't known here. */
    private static FleetMemberAPI buildMember(String id, JSONObject m, List<String> problems) throws JSONException {
        ShipVariantAPI variant = buildVariant(m, problems, "ship " + id);
        if (variant == null) return null;
        FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, variant);
        member.setId(id); //other systems (and the next diffs) key on it
        member.setShipName(m.optString("shipName", ""));
        String personality = m.optString("personalityOverride", null);
        if (personality != null) member.setPersonalityOverride(personality);
        return member;
    }

    /**
     * A variant from the record: an empty one of the exact hull (a D-hull's own spec), then the fit. REFIT as vanilla's
     * own player variants (DModManager.setDHull), so it's saved as it is rather than reloaded by its id.
     */
    private static ShipVariantAPI buildVariant(JSONObject r, List<String> problems, String what) throws JSONException {
        String hullId = r.getString("hullId");
        ShipHullSpecAPI hull = spec(() -> Global.getSettings().getHullSpec(hullId));
        if (hull == null) {
            problems.add(what + ": unknown hull " + hullId);
            return null;
        }
        String variantId = r.optString("variantId", Misc.genUID());
        ShipVariantAPI variant = Global.getSettings().createEmptyVariant(variantId, hull);
        variant.setSource(VariantSource.REFIT);
        variant.setHullVariantId(variantId);
        //in case an empty variant lacks the hull's built-in weapons and wings (only filled where missing)
        for (Map.Entry<String, String> builtIn : hull.getBuiltInWeapons().entrySet()) {
            if (variant.getWeaponId(builtIn.getKey()) == null) variant.addWeapon(builtIn.getKey(), builtIn.getValue());
        }
        List<String> builtInWings = hull.getBuiltInWings();
        for (int i = 0; i < builtInWings.size(); i++) {
            if (variant.getWingId(i) == null && builtInWings.get(i) != null) variant.setWingId(i, builtInWings.get(i));
        }
        variant.setNumFluxVents(r.optInt("vents", 0));
        variant.setNumFluxCapacitors(r.optInt("caps", 0));

        for (String mod : keys(r.optJSONObject("mods"))) {
            if (hullMod(mod, problems, what)) variant.addMod(mod);
        }
        for (String mod : keys(r.optJSONObject("permaMods"))) {
            if (hullMod(mod, problems, what)) variant.addPermaMod(mod, false);
        }
        for (String mod : keys(r.optJSONObject("sMods"))) {
            if (hullMod(mod, problems, what)) variant.addPermaMod(mod, true);
        }
        for (String mod : keys(r.optJSONObject("sModdedBuiltIns"))) {
            if (hullMod(mod, problems, what)) variant.getSModdedBuiltIns().add(mod); //no setter: the live set
        }
        for (String mod : keys(r.optJSONObject("suppressed"))) {
            if (hullMod(mod, problems, what)) variant.addSuppressedMod(mod);
        }

        JSONObject weapons = r.optJSONObject("weapons");
        for (String slot : keys(weapons)) {
            String weapon = weapons.getString(slot);
            if (!known(() -> Global.getSettings().getWeaponSpec(weapon))) {
                problems.add(what + ": unknown weapon " + weapon);
                continue;
            }
            //a slot the hull no longer has (a mod update) costs that weapon, not the ship
            try {
                variant.addWeapon(slot, weapon);
            } catch (RuntimeException e) {
                problems.add(what + ": weapon " + weapon + " in slot " + slot + ": " + e);
            }
        }
        JSONObject wings = r.optJSONObject("wings");
        for (String bay : keys(wings)) {
            String wing = wings.getString(bay);
            if (!known(() -> Global.getSettings().getFighterWingSpec(wing))) {
                problems.add(what + ": unknown fighter wing " + wing);
                continue;
            }
            try {
                variant.setWingId(Integer.parseInt(bay), wing);
            } catch (RuntimeException e) {
                problems.add(what + ": fighter wing " + wing + " in bay " + bay + ": " + e);
            }
        }

        //the groups as they were: the game would otherwise make its own from the weapons (as in FleetSerializer)
        JSONObject groups = r.optJSONObject("groups");
        if (groups != null) {
            variant.setMayAutoAssignWeapons(false);
            variant.getWeaponGroups().clear();
            List<String> indices = new ArrayList<>(keys(groups));
            indices.sort(Comparator.comparingInt(Integer::parseInt));
            for (String index : indices) {
                JSONObject g = groups.getJSONObject(index);
                WeaponGroupSpec spec = new WeaponGroupSpec(WeaponGroupType.valueOf(g.getString("type")));
                spec.setAutofireOnByDefault(g.optBoolean("autofire"));
                List<String> slots = new ArrayList<>(keys(g.optJSONObject("slots")));
                Collections.sort(slots);
                for (String slot : slots) spec.addSlot(slot);
                variant.addWeaponGroup(spec);
            }
        }

        Set<String> tags = keys(r.optJSONObject("tags"));
        for (String tag : new ArrayList<>(variant.getTags())) {
            if (!tags.contains(tag)) variant.removeTag(tag);
        }
        for (String tag : tags) variant.addTag(tag);
        String displayName = r.optString("displayName", null);
        if (displayName != null) variant.setVariantDisplayName(displayName);

        JSONObject modules = r.optJSONObject("modules");
        for (String slot : keys(modules)) {
            ShipVariantAPI module = buildVariant(modules.getJSONObject(slot), problems, what + " module " + slot);
            if (module == null) continue;
            variant.setModuleVariant(slot, module);
            //an empty variant may not name its modules: the slot's entry is what the game loads by id otherwise
            try {
                if (!variant.getStationModules().containsKey(slot)) variant.getStationModules().put(slot, module.getHullVariantId());
            } catch (UnsupportedOperationException e) {
                MultiplayerLog.log().warn(what + ": can't name module " + slot + " in the variant (" + e + ")");
            }
        }
        return variant;
    }

    private static boolean hullMod(String id, List<String> problems, String what) {
        if (known(() -> Global.getSettings().getHullModSpec(id))) return true;
        problems.add(what + ": unknown hullmod " + id);
        return false;
    }

    /** An AI core captain: the core's own person (portrait, personality, automated points), then the record's state. */
    private static PersonAPI buildAICore(JSONObject r, List<String> problems, String what) throws JSONException {
        String coreId = r.optString("aiCore", null);
        AICoreOfficerPlugin plugin = coreId == null ? null : Misc.getAICoreOfficerPlugin(coreId);
        if (plugin == null) {
            problems.add(what + ": unknown AI core " + coreId);
            return null;
        }
        PersonAPI core = plugin.createPerson(coreId, Factions.PLAYER, new Random());
        MutableCharacterStatsAPI stats = core.getStats();
        stats.setSkipRefresh(true);
        try {
            writeSkills(stats, r.optJSONObject("skills"), problems, what + "'s AI core");
            stats.setLevel(r.optInt("level", stats.getLevel()));
        } finally {
            stats.setSkipRefresh(false);
        }
        writeMemory(core, r.optJSONObject("memory"));
        stats.refreshCharacterStatsEffects();
        return core;
    }

    /** An officer's or admin's recorded state onto a person (a new one, or this game's own of the same id). */
    private static void writePerson(PersonAPI person, JSONObject o, List<String> problems, String what) throws JSONException {
        FullName.Gender gender = gender(o.optString("gender", null));
        person.setName(new FullName(o.optString("first", ""), o.optString("last", ""), gender));
        person.setGender(gender);
        if (o.has("portrait")) person.setPortraitSprite(o.getString("portrait"));
        if (o.has("personality")) person.setPersonality(o.getString("personality"));
        if (o.has("rank")) person.setRankId(o.getString("rank"));
        if (o.has("post")) person.setPostId(o.getString("post"));
        MutableCharacterStatsAPI stats = person.getStats();
        stats.setSkipRefresh(true);
        try {
            writeSkills(stats, o.optJSONObject("skills"), problems, what);
            stats.setLevel(o.optInt("level", 1));
            stats.setXP(o.optLong("xp", 0));
        } finally {
            stats.setSkipRefresh(false);
        }
        writeMemory(person, o.optJSONObject("memory"));
        stats.refreshCharacterStatsEffects();
    }

    /** Skills equal to the record's: the others set to 0, aptitudes left alone. */
    private static void writeSkills(MutableCharacterStatsAPI stats, JSONObject skills, List<String> problems, String what) throws JSONException {
        if (skills == null) skills = new JSONObject();
        for (MutableCharacterStatsAPI.SkillLevelAPI skill : stats.getSkillsCopy()) {
            if (skill.getSkill() == null || skill.getSkill().isAptitudeEffect() || skill.getLevel() <= 0) continue;
            if (!skills.has(skill.getSkill().getId())) stats.setSkillLevel(skill.getSkill().getId(), 0);
        }
        for (String id : keys(skills)) {
            SkillSpecAPI spec = spec(() -> Global.getSettings().getSkillSpec(id));
            if (spec == null) {
                problems.add(what + ": unknown skill " + id);
                continue;
            }
            if (spec.isAptitudeEffect()) continue;
            float level = (float) skills.getDouble(id);
            if (stats.getSkillLevel(id) != level) stats.setSkillLevel(id, level);
        }
    }

    /**
     * The whitelisted memory keys equal to the record's, each with the type the game's code reads it as (JSON only
     * has numbers: a hire timestamp is a long, the automated-ship points floats, the officer limits ints).
     */
    private static void writeMemory(PersonAPI person, JSONObject memory) {
        MemoryAPI mem = person.getMemoryWithoutUpdate();
        for (String key : PERSON_MEMORY) {
            Object value = memory == null ? null : memory.opt(key);
            if (value == null || value == JSONObject.NULL) {
                mem.unset(key);
            } else if (value instanceof Number) {
                Number n = (Number) value;
                if (key.equals(Misc.MERCENARY_HIRE_TIMESTAMP)) mem.set(key, n.longValue());
                else if (key.equals(AICoreOfficerPlugin.AUTOMATED_POINTS_VALUE) || key.equals(AICoreOfficerPlugin.AUTOMATED_POINTS_MULT)) mem.set(key, n.floatValue());
                else if (n.doubleValue() == Math.rint(n.doubleValue())) mem.set(key, n.intValue());
                else mem.set(key, n.floatValue());
            } else {
                mem.set(key, value);
            }
        }
    }

    /** Idle admins equal to the record's; the ones governing a colony are the colony's and stay. */
    private static void applyAdmins(JSONObject admins, List<String> problems) throws JSONException {
        if (admins == null) return;
        CharacterDataAPI data = Global.getSector().getCharacterData();
        Map<String, PersonAPI> idle = new HashMap<>();
        Set<String> governing = new HashSet<>();
        for (AdminData admin : new ArrayList<>(data.getAdmins())) {
            if (admin.getPerson() == null) continue;
            if (admin.getMarket() != null) {
                governing.add(admin.getPerson().getId());
            } else {
                idle.put(admin.getPerson().getId(), admin.getPerson());
                if (!admins.has(admin.getPerson().getId())) data.removeAdmin(admin.getPerson());
            }
        }
        for (Iterator<?> it = admins.keys(); it.hasNext(); ) {
            String personId = (String) it.next();
            if (governing.contains(personId)) continue; //this game has them governing since: the colony's say
            PersonAPI person = idle.get(personId);
            boolean added = person == null;
            if (added) {
                person = Global.getFactory().createPerson();
                person.setId(personId);
                person.setFaction(Factions.PLAYER);
            }
            writePerson(person, admins.getJSONObject(personId), problems, "admin " + personId);
            if (added) data.addAdmin(person);
        }
    }

    private static void applyCargo(JSONObject cargo, List<String> problems) throws JSONException {
        if (cargo == null) return;
        CargoAPI playerCargo = Global.getSector().getPlayerFleet().getCargo();
        writeStacks(playerCargo, cargo.optJSONObject("stacks"), problems, "cargo");
        if (cargo.has("credits")) playerCargo.getCredits().set((float) cargo.getDouble("credits"));
    }

    /** The cargo's stacks equal to the record's (emptied stack by stack: clear() isn't documented to spare the rest). */
    private static void writeStacks(CargoAPI cargo, JSONObject stacks, List<String> problems, String what) throws JSONException {
        for (CargoStackAPI stack : cargo.getStacksCopy()) stack.setSize(0);
        cargo.removeEmptyStacks();
        for (String key : keys(stacks)) {
            JSONObject s = stacks.getJSONObject(key);
            String id = s.getString("id");
            float qty = (float) s.getDouble("qty");
            CargoAPI.CargoItemType type;
            try {
                type = CargoAPI.CargoItemType.valueOf(s.getString("type"));
            } catch (IllegalArgumentException e) {
                problems.add(what + ": unknown stack type " + s.optString("type"));
                continue;
            }
            Object data;
            boolean exists;
            switch (type) {
                case RESOURCES:
                    exists = known(() -> Global.getSettings().getCommoditySpec(id));
                    data = id;
                    break;
                case WEAPONS:
                    exists = known(() -> Global.getSettings().getWeaponSpec(id));
                    data = id;
                    break;
                case FIGHTER_CHIP:
                    exists = known(() -> Global.getSettings().getFighterWingSpec(id));
                    data = id;
                    break;
                case SPECIAL:
                    exists = known(() -> Global.getSettings().getSpecialItemSpec(id));
                    data = new SpecialItemData(id, s.optString("data", null));
                    break;
                default:
                    exists = false;
                    data = id;
            }
            if (!exists) {
                problems.add(what + ": unknown " + type.name().toLowerCase(Locale.ROOT) + " item " + id);
                continue;
            }
            cargo.addItems(type, data, qty);
        }
    }

    /**
     * Every market's storage here (the player's colonies' too) emptied, then filled from the record. The record's
     * entries for markets this game doesn't have are carried (see carriedStorage).
     */
    private static int applyStorage(JSONObject storage, List<String> problems) throws JSONException {
        if (storage == null) return 0;
        Map<String, MarketAPI> markets = storageMarkets();
        int filled = 0;
        for (MarketAPI market : markets.values()) {
            //one market that fails doesn't keep the others from theirs
            try {
                if (fillStorage(market, storage.optJSONObject(market.getId()), problems)) filled++;
            } catch (JSONException | RuntimeException e) {
                MultiplayerLog.log().error("Applying the player record's storage at market " + market.getId() + " failed", e);
                problems.add("storage at " + market.getId() + ": " + e);
            }
        }
        for (String id : keys(storage)) {
            JSONObject entry = storage.optJSONObject(id);
            if (markets.containsKey(id) || entry == null) continue;
            carriedStorage.put(id, entry);
            MultiplayerLog.log().info("The player record has storage at market " + id + ", which this game doesn't have (yet): kept, and put there if it's made");
        }
        return filled;
    }

    /** A market's storage emptied, then filled from its record entry (none: left empty). Whether there was one. */
    private static boolean fillStorage(MarketAPI market, JSONObject entry, List<String> problems) throws JSONException {
        SubmarketAPI submarket = market.getSubmarket(Submarkets.SUBMARKET_STORAGE);
        CargoAPI cargo = submarket == null ? null : submarket.getCargo();
        if (cargo == null) return false;
        String what = "storage at " + market.getId();
        writeStacks(cargo, entry == null ? null : entry.optJSONObject("stacks"), problems, what);
        FleetDataAPI mothballed = cargo.getMothballedShips();
        if (mothballed != null) {
            for (FleetMemberAPI member : mothballed.getMembersListCopy()) mothballed.removeFleetMember(member);
        }
        JSONObject ships = entry == null ? null : entry.optJSONObject("ships");
        if (ships != null && ships.length() > 0) {
            if (mothballed == null) {
                cargo.initMothballedShips(Factions.PLAYER);
                mothballed = cargo.getMothballedShips();
            }
            Map<FleetMemberAPI, JSONObject> built = new LinkedHashMap<>();
            for (String id : byOrder(ships)) {
                JSONObject m = ships.getJSONObject(id);
                FleetMemberAPI member;
                try {
                    member = buildMember(id, m, problems);
                    if (member != null && m.opt("captain") instanceof JSONObject) {
                        PersonAPI core = buildAICore(m.getJSONObject("captain"), problems, what + " ship " + id);
                        if (core != null) member.setCaptain(core);
                    }
                } catch (JSONException | RuntimeException e) {
                    MultiplayerLog.log().error("Building ship " + id + " of the player record's " + what + " failed", e);
                    problems.add(what + " ship " + id + ": " + e);
                    continue;
                }
                if (member == null) continue;
                mothballed.addFleetMember(member);
                built.put(member, m);
            }
            mothballed.setSyncNeeded();
            mothballed.syncIfNeeded();
            for (Map.Entry<FleetMemberAPI, JSONObject> e : built.entrySet()) writeMemberState(e.getKey(), e.getValue());
        }
        if (entry == null) return false;
        //what the player stored there was theirs to reach: vanilla only asks to pay before the first use
        if (submarket.getPlugin() instanceof StoragePlugin) ((StoragePlugin) submarket.getPlugin()).setPlayerPaidToUnlock(true);
        return true;
    }

    /**
     * Markets with a storage submarket, by id, the player's own colonies included (their storage holds ships and
     * items like any other; only their economy stays out of the record). Every location's entities, not only the
     * economy's markets: an abandoned station's storage isn't in the economy.
     */
    private static Map<String, MarketAPI> storageMarkets() {
        Map<String, MarketAPI> markets = new LinkedHashMap<>();
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) addStorageMarket(markets, market);
        for (LocationAPI location : Global.getSector().getAllLocations()) {
            for (SectorEntityToken entity : location.getAllEntities()) addStorageMarket(markets, entity.getMarket());
        }
        return markets;
    }

    private static void addStorageMarket(Map<String, MarketAPI> markets, MarketAPI market) {
        if (market == null || markets.containsKey(market.getId())) return;
        if (market.getSubmarket(Submarkets.SUBMARKET_STORAGE) == null) return;
        markets.put(market.getId(), market);
    }

    /**
     * Puts the fleet where the record has it: straight there, as the mod moves copies between locations (no jump, so
     * no transition to get stuck in), then makes it the location shown.
     */
    private static void applyLocation(JSONObject location) throws JSONException {
        if (location == null) return;
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        String id = location.getString("id");
        float x = (float) location.getDouble("x"), y = (float) location.getDouble("y");
        LocationAPI target = null;
        if ("hyperspace".equals(id) || Global.getSector().getHyperspace().getId().equals(id)) {
            target = Global.getSector().getHyperspace();
        } else {
            for (LocationAPI l : Global.getSector().getAllLocations()) {
                if (l.getId().equals(id)) target = l;
            }
        }
        if (target == null) {
            MultiplayerLog.log().warn("The player record's location " + id + " isn't in this game: the fleet stays where it is");
            return;
        }
        LocationAPI from = fleet.getContainingLocation();
        if (from != target) {
            fleet.setOrbit(null);
            if (from != null) from.removeEntity(fleet);
            target.addEntity(fleet);
            Global.getSector().setCurrentLocation(target);
            Global.getSector().getCampaignUI().resetViewOffset(); //as vanilla's respawn does after its move
        }
        fleet.setLocation(x, y);
        fleet.getVelocity().set(0, 0);
        fleet.setMoveDestination(x, y);
    }

    // ------------------------------------------------------------------ helpers

    private static JSONObject set(Collection<String> ids) throws JSONException {
        JSONObject set = new JSONObject();
        if (ids == null) return set;
        for (String id : ids) {
            if (id != null) set.put(id, true);
        }
        return set;
    }

    private static Set<String> keys(JSONObject object) {
        Set<String> keys = new LinkedHashSet<>();
        if (object == null) return keys;
        for (Iterator<?> it = object.keys(); it.hasNext(); ) keys.add((String) it.next());
        return keys;
    }

    private static void matchSet(Set<String> live, Set<String> wanted) {
        live.retainAll(wanted);
        live.addAll(wanted);
    }

    /** Members' ids in the fleet's order (the map has none of its own). */
    private static List<String> byOrder(JSONObject ships) {
        List<String> ids = new ArrayList<>(keys(ships));
        ids.sort(Comparator.comparingInt(id -> ships.optJSONObject(id) == null ? 0 : ships.optJSONObject(id).optInt("order", 0)));
        return ids;
    }

    private static FullName.Gender gender(String name) {
        try {
            return name == null ? FullName.Gender.ANY : FullName.Gender.valueOf(name);
        } catch (IllegalArgumentException e) {
            return FullName.Gender.ANY;
        }
    }

    /** A skill level as JSON: whole levels as ints (1, 2 = elite). */
    private static Object level(float level) {
        return level == Math.rint(level) ? (Object) (int) level : (Object) num(level);
    }

    /** JSON can't hold NaN or infinities (put throws): those become 0. */
    private static double num(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? 0 : value;
    }

    /** A spec getter's result, or null: some of the game's getters throw for an unknown id instead of returning null. */
    private static <T> T spec(Supplier<T> getter) {
        try {
            return getter.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean known(Supplier<?> getter) {
        return spec(getter) != null;
    }
}
