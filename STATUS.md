# Multiplayer fork: status and handoff

Read this first when resuming. Keep it updated (and committed) after every change, test or finding: the user's
token budget runs out between sessions, so work must always be resumable from here.

## Setup
- Repo: fork `ERRORCODE509/multiplayer` (`origin`); the original `moi75ts/multiplayer` is `upstream`. Each branch is
  a git worktree under `D:\Software\starsector_modding\multiplayer\` (`master` is the root folder, `dev`, `fix-*`,
  `feature-*` subfolders). **All current work is on `dev`** (`multiplayer\dev`); `feature/server-instance` was merged
  into it and is no longer worked on separately.
- Game: Starsector 0.98a-RC8 at `C:\Program Files (x86)\Fractal Softworks\Starsector`, running with the user's tuned
  `vmparams` (many exotic -XX flags; reproduce JVM issues with them). `mods\dev` is a link to the `dev` worktree, so
  a build is deployed as soon as the jars are written. Mod id `matlabmaster_multiplayer`, enabled in
  `mods\enabled_mods.json`.
- Logs: player game `starsector-core\starsector.log` (rotates to `.log.1`); dedicated server instance
  `logs-server\starsector.log`. The server's world save is in `saves-server`. The mod logs with the prefix
  `multiplayer  -`.
- Testing: the user runs `mods\dev\launcher\Start Server Launcher.bat` -> START SERVER (a second, dedicated game
  instance that hosts on port 20603 once a save is loaded there) -> START MY GAME, then JOIN 127.0.0.1 in the
  multiplayer window. The user does the in-game testing and reports back; read both logs to diagnose.

## Build and push (Git Bash)
```
cd /d/Software/starsector_modding/multiplayer/dev
S=<scratch dir>; C="C:/Program Files (x86)/Fractal Softworks/Starsector/starsector-core"
CP=$(for j in starfarer.api.jar starfarer_obf.jar json.jar lwjgl.jar lwjgl_util.jar log4j-1.2.9.jar xstream-1.4.10.jar fs.common_obf.jar; do printf "%s;" "$C/$j"; done)"jars/libs/compare-utils-1.1.0.jar"
rm -rf "$S" && mkdir -p "$S/mod" && javac --release 17 -nowarn -encoding UTF-8 -d "$S/mod" -cp "$CP" $(find src -name '*.java')
(cd src && find . -type f ! -name '*.java' -exec cp --parents {} "$S/mod/" \;) && jar cf jars/multiplayer.jar -C "$S/mod" .
```
- The jars are committed (CI doesn't build them): rebuild `jars/multiplayer.jar` with every source change.
- Agent (`agent/src`, -> `launcher/MultiplayerAgent.jar`, manifest `Premain-Class`): compile against
  `starfarer.api.jar;lwjgl_util.jar`. Launcher (`launcher/src` -> `launcher/MultiplayerLauncher.jar`, `Main-Class`).
- **The jars are locked while any game or the launcher runs** (check `Get-Process java,javaw`): ask the user to close
  them, then build.
- Before committing, compare `git diff --cached --numstat` with `-w --numstat`: they must match (an edit with `sed`
  can rewrite a CRLF file as LF; `Server.java` is LF since `8ebf8ea`, `Client.java` is CRLF).
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Push with
  `git push origin dev`; HTTPS sometimes drops mid-push: retry, or one commit at a time
  (`for c in $(git rev-list --reverse origin/dev..dev); do git push origin $c:refs/heads/dev || break; done`).
  History rewrites (filter-branch, force push) are blocked by the permission classifier: don't.
- Code style: comments say why, in plain sentences, at the density of the surrounding code; no new docs files.

## License
MIT (`LICENSE`, ERROR_CODE 509); the original project's code (MatlabMaster, moi75ts/multiplayer) stays under the
Unlicense, as its developer stated (also in `LICENSE`).

## Dev tools: starsector-mcp + agent bridge (local only)
- Everything under `tools/` is gitignored and never published (keeps the repo MIT-only): don't commit it, and
  don't read or use the coop mod's own code (AyoKeito/starsector-coop; other license, the user's call).
- `tools/starsector-mcp/`: that project's MCP server (only its folder, as downloaded). Registered in
  `D:\Software\starsector_modding\.mcp.json` as `starsector`. **The user must run `npm ci` in it once** (running
  third-party code needs their go-ahead), then approve the server when Claude Code starts. Tools: `ss_status`,
  `ss_dump`, `ss_diff`, `ss_act`, `ss_advance_days` (instance "host" = port 7801, "guest" = 7802).
- `tools/agent-bridge/`: our own bridge for it, a separate dev mod (`mp_agent_bridge`, depends on the multiplayer
  mod), linked as `mods\mp-agent-bridge` and enabled in `enabled_mods.json`. Build with
  `sh tools/agent-bridge/build.sh` (after the main jar). Listens on 127.0.0.1: 7801 in the server instance, 7802 in
  a player's game (`-Dmultiplayer.bridge.port=N` overrides). Newline JSON, `{id, cmd, args}` ->
  `{id, ok, data | error}`, run a few per frame on the game thread.
  Reads: `status` (role HOST/GUEST/NONE, clock, player fleet, multiplayer block: players, client id, faction),
  `fleets` ({locationId?|"all", near?}), `cargo`, `markets`, `market` ({marketId}, no restock), `entities`
  ({locationId?}: orbit angles), `screen`, `raids` (every FleetGroupIntel: `world:<id>` / `handed:<id>`, action,
  fleets, targets' stability/unrest/disruption). Actions: `pause` (connected games unpause themselves), `teleport`,
  `give`, `addship`, `setcr`, `ability`, `rep` ({factionId, value}), `mark` ({text} -> "[AGENT MARK]" in the log),
  `memory`, `raid` ({kind = raid|blockade|takeover, factionId?, marketId?, sourceId?, fleets?, prepDays = 1,
  payloadDays = 20}: a crisis-like raid, League blockade or Knights takeover of the player's colony). Works in-game (status, fleets, entities, ss_diff checked). `entities` skips asteroids: they're never
  synced (each game's belts and fields make their own, with their own ids).
- Fleet ids are the same in every game of a session, so `ss_diff(what: "fleets", args: {near: 3000, around:
  "<client id>"})` compares the server's and a client's view around the client's fleet (the server has every fleet,
  a client only nearby ones; on a dedicated server the local player fleet is a stand-in, hence `around`).
  `{fleetId: "a,b"}` picks fleets anywhere. Each fleet has a per-game `vsPlayer` block (ignore it in diffs).

## Architecture (what's where)
- The **server's game is the only authority on the world** (NPC fleets, clock, markets, economy); each **player's
  game is the authority on their own fleet, reputation and colonies**. Clients strip sector scripts while connected
  (`utils/SectorScriptsUtility`, keeps core-engine scripts and `BaseEventIntel` events) and show server-driven copies.
- Protocol version 8 (`server/Server.PROTOCOL_VERSION`). Join: `welcome` (client id) -> client sends `hello` (permanent
  player id from `utils/PlayerIdentity`, name) -> server reserves a player faction (`server/PlayerRegistry`, kept in
  the world save's persistent data) and replies `yourFaction`.
- `server/Server` message dispatch (network threads; game work goes through `gameThreadTasks`). `server/ServerScripts`
  per-frame: fleet updates per client (`updates/FleetSync`, `VisibleFleets`), clock, dialog holds/pins and
  interceptions, dedicated own-fleet hiding (no signature/fuel/supplies), agent data. `server/ServerFactionSync`
  (relations, player faction looks, colonies), `server/ServerMarkets` (market stock and trades), `server/ServerDebris`
  (battle debris: relays players' fields, shares the server game's own, sends them all on joining).
- Client: `client/Client` (connection, `completeJoin`), `client/ClientScripts` (message handling, per-second sends:
  reputation/blueprints/name, colonies, debris, copy AIs), `ClientMarkets`, `InteractionOrbit`,
  `CopyAI` (NPC copies decide as vanilla's, never move by themselves). `utils/PlayerEncounters` (no-combat dialog
  for players' fleets), `utils/PositionSmoothing`
  (gradual position corrections). `utils/PauseUtility` sends `paused`/`unpaused` (dialog target and positions).
- Shared: `utils/PlayerFactions` (32 player factions `mp_player_N` in `data/world/factions`), `utils/ColonyMirrors`
  (other players' colonies: in the server's economy, display-only elsewhere), `updates/MarketSync`, `BattleSync`,
  `DebrisSync`, `utils/FleetSerializer`/`PersonsSerializer`.
- Crisis raids (`updates/RaidSync`, `server/ServerRaids`, `client/OwnRaids`): the owner's crisis makes a raid
  (GenericRaidFGI and subclasses, not blockades); their game hands it over (`raidHandOver`), keeps its own frozen
  (out of the scripts, route removed, kept in its save) and the server's game makes the same one on the world's
  copy of the colony (same market ids), kept in the world's save. Its fleets are world NPC fleets: every player
  sees them, and battles against them (BattleSync) make the vanilla raid abort. The server sends `raidAction`
  (intel updates), `raidEnded` (aborted -> the owner's raid aborts, so the crisis counts it as beaten; finished;
  cancelled) and `colonyHit` (unrest, disruption, pollution the world's copies of colonies took, from any raid);
  queued in the registry while the owner is offline. The owner's crisis ending it sends `raidCallOff`.
  Blockades too: the world runs `updates/WorldBlockades` (vanilla's, minus the check for the host's own crisis);
  the owner's game puts the League's "Blockaded" condition on while the world blockades, and the Knights' unrest
  and takeover stay in the owner's game (vanilla's listener on its frozen copy; removed from the world's). A
  colony lost to another faction sends `colonyLost` (the world's copy becomes theirs, `ColonyMirrors.release`); a
  world copy decivilized (saturation bombardment) tells the owner through a `ColonyDecivListener`.
- Players' colonies: `rules.csv` `mp_noAttackPlayerColony` removes "Consider your military options" at a mirror
  (`$mp_playerColony`); `ColonyMirrors.create` never replaces another player's mirror.
  `utils/FleetFlags`: NPC fleets carry their `$cfai_makeHostile_<faction>` and rep-impact flags to the copies
  (made hostile to your faction = hostile to you); the raid's plain `$cfai_makeHostile` (the host) is cleared.
- Shared world (protocol 8): `updates/EntitySync` + `server/ServerEntities` + `client/WorldEntities` (salvageable
  things: `worldEntities` every 3 s per player's location, `entityGone` from a player, `worldEntityGone` to the
  rest, `worldEntitiesGone` on joining from the registry's "entitiesGone"; only ids the world listed are touched).
  `updates/WorldOwnership` + `server/ServerOwnership` (`worldOwnership`: markets' owners and sizes, objectives'
  owners, removed markets; all on joining, changes every 5 s). `updates/WorldMarkets` (`requestWorldMarket` ->
  `worldMarket`: a copy of a world market the player's game lacks, station included; `$mp_worldMarketCopy`).
  `updates/WorldBounties` + `client/BountyBoard`/`WorldBountyIntel` (`worldBounties` board, `bountyReward` from
  BattleSync for a person bounty's target; system bounties paid client-side after its battles).
- Agent (`-javaagent`, added by the launcher): `NearestPlayer` (fleet spawning around every player),
  `FullRateLocations` (locations with a player run every frame; patches `CampaignEngine.advance`, 17 calls).
- Colony tariffs: `rulecmd/MP_Tariff` + `data/campaign/rules.csv` + `data/config/settings.json`.

## Needs testing (latest first)
- [ ] **More of the world shared (protocol 8: `9c18451`, `254fc36`, `1b59692`, `5c19c53`, `ab4fc94`, `b3beae8`,
  untested; also `f9952a2`, `a9a7dea`, `1257987`, `60be6c7`).** Rebuild done; restart both games (both need the new jar).
  1. Salvage: salvage something in the world (a derelict ship, a cache, a probe) from your game: your log "Salvaged
     <name>: the world hears of it", server log "<name> salvaged <name>: gone from the world"; it's gone from the
     server's instance and any other player's game. Salvaged from the server's instance: gone from yours within a
     few seconds while you're in that system ("... gone from the world ... gone here too"). A player joining later
     doesn't find it either.
  2. World owners: change a market's owner on the server (Nexerelin invasion, or console `setmarketowner`) or a
     relay's: within 5 s your game shows the new owner (campaign log "<market> is now <faction>'s"); on joining,
     your game takes all the world's owners and sizes (log "The world's owners: N markets ... changed here").
  3. Bounties: the intel tab (Bounties) lists the world's person and system bounties while connected (gone after
     leaving, never in your save). Beat a person bounty's target from your game: "Bounty on <name> collected ...",
     credits and reputation, the bounty leaves every player's list. Fight hostile ships near a system bounty's
     market: "System bounty at <market>: N credits received".
  4. World markets: a pirate base (or a Nexerelin colony) the world founded appears in your game (log "The world's
     <name> (pirates) is here too (with its station)"), hidden until discovered like any base; trading there uses
     the world's stock; destroyed in the world, it's gone from your game; gone from your game after leaving. On
     joining, the log lists the markets your save has that the world doesn't.
  5. Debris (`a9a7dea`): a field from another player's battle holds that battle's salvage (not a small default) and
     its ships to recover; a second battle on the same field, or ships recovered from it, reach the others' copies.
  6. Your game's own fleets (`1257987`, `60be6c7`): a mission's fleet (a bar event's target, a contact's) moves and
     acts normally while connected (it froze before); joining keeps the fleets your missions need (log "Kept N
     fleets this game's missions need") instead of removing them with the rest.
- [ ] **Fixes from the 2026-10-01 raid tests (`f946e9e`, `f041d45`, untested; `f041d45` is titled "STATUS.md: ..." but also holds the code: dedicated server visitor trades, ending raids finish).**
  1. Another player's colony (e.g. the server's instance at a client's colony): "Consider your military options"
     is greyed out with a tooltip; Trade and Esc work again (removing the option had broken that menu).
  2. Then D: trading there from the server's instance pays the owner a tariff (the owner's own trades never do:
     vanilla charges no tariff at your own colony). On a dedicated server its own fleet's trades at a player's
     colony didn't reach the owner at all (only "host current game" did): now they do (server log "The server's own
     fleet traded at <colony> (sent to its owner)", owner's log "A visitor traded at ..."). Also check the colony's
     ships are for sale there (the world's copy showed none).
  7. When the world ends a raid, your intel finishes it while you're connected (it stayed "ending" until you
     left).
  3. A raid that raids your colony: your intel says "The ... are withdrawing" (not "failed"/"defeated"); the hit
     says "(Luddic Path raid)" etc., not "(Raid)".
  4. A raid made with a prep time (`ss_act raid` prepDays: 3) waits that long in the world before leaving.
  5. The world's copy of your colony has your stability (`raids` targets: the same on both; it showed 10 vs 4).
  6. The dedicated server's own fleet stays at the supplies and fuel it had, also after battles.
- [ ] **Players' colonies can't be attacked by other players; blockades and saturation bombardments run in the world
  (`fa020c1`).** Tested OK by the user (2026-10-01): 1 (no military options), 2 (a League blockade: handed over,
  spawned, arrived, the owner was told, beaten by another player (the host) and ended as defeated in both games).
  Left: 3, 4, and whether the colony showed "Blockaded" while blockaded (not checked). The "not attackable" part
  broke Trade and Esc at those colonies: fixed above.
  1. Docking at another player's colony (online or offline): no "Consider your military options" (so no engage,
     raid, bombard, or Nexerelin invade); trading still works. The same at your own is unchanged.
  2. `ss_act raid {kind: "blockade"}` in the guest (Persean League blockade of your colony's system): handed over
     as a raid is; the League fleets (armada, two supply fleets) appear in the world; once blockading, your colony
     shows "Blockaded" (accessibility down, `ss_dump raids` "blockaded"); beating the armada or both supply fleets
     (any player) ends it as defeated.
  3. `ss_act raid {kind: "takeover"}` on a colony with a Luddic majority: the Knights blockade it in the world; each
     month while blockading your colony takes the takeover unrest (your game's vanilla code, not twice); at 0
     stability it's the Church's: your log "We lost <colony> to the Luddic Church", server log "... it's theirs in
     the world too", and the world's market is the Church's (other players' games just drop the mirror).
  4. Saturation bombardment of a colony's world copy (hard to trigger; console on the server): the owner's colony
     loses the same size, or is destroyed (decivilized) if the world's copy was.
- [ ] **Crisis raids run in the world (`d47eedc`, protocol 7).** Tested OK (2026-10-01): handover, spawning, being
  seen, being beaten by another player (blockade and pirate raids), a Luddic Path raid's hits on the colony (-3
  stability twice, raidsPerColony 2). Left: a pirate raid's hostility to its owner (intercepts), the owner offline
  (5). Found and fixed above: the owner's intel told a successful raid as failed; subclasses ran as plain raids.
  Quickest: bridge `ss_act raid` in the player's game (guest) while connected, with a colony
  (`{factionId: "pirates", fleets: [3, 2], prepDays: 1}`); a real crisis raid works the same way.
  1. Guest log: "A Pirate Raid is coming for <system>: the world runs it (<id>)"; server log: "<name> handed over a
     raid on their colony", "The world runs a Pirate Raid on <system> (from <market>)". `ss_dump raids` on both:
     guest `handed:<id>`, `running: false`; host `world:<id>`, `running: true`.
  2. The raid's fleets appear near players as it travels (any player within ~1.6 LY of its route), are hostile to
     the colony's owner (tooltip, they chase and intercept them) and seen by every player; for the others they're
     as hostile as their faction is to them (pirates: hostile). The owner's intel updates as it launches/arrives.
  3. Any player (not only the owner) beating most of its fleets: the server log says it "was defeated or called
     off", the owner's log "The Pirate Raid on <system> was defeated" and their intel shows it defeated (a real
     crisis then counts it as beaten, e.g. piracy respite).
  4. Left alone, it raids the world's copy of the colony: server log "<colony> (<player>) was hit: {...}", owner's
     log "The world's raid hit <colony>: -N stability (Pirate raid)..." and the colony screen shows the unrest
     (and any disruption).
  5. Owner offline meanwhile: the news (hits, how it ended) arrives when they join again.
Tested by the user on 2026-10-01: smoothing (good now), players list, no PvP, [PAUSED] (probably), jump flash (players
only), hosting/tariff, setting the tariff, left alone in dialogs, orbit after a dialog (not while sped up), rename,
jumping between locations, construction catch-up, reputation sound (mostly: sometimes doesn't play).
Not tested yet: campaign messages, losing the whole fleet, debris.

**Found by the user (2026-10-01):**
- A. Other players' fleets are seen by normal detection only: by design (a bigger detected range would show them
  to NPC fleets too). The boost that tried it (and didn't work) is gone (`d817355`).
- C. **Won't fix:** time speed-up isn't supported in multiplayer (the user still uses it sometimes). Clients can't
  follow it (no API to fast-forward a game), so they resync constantly meanwhile; it runs fine otherwise.
- [x] B. NPC fleets that jump in or out of a client's sight flash as players' do (`d817355`).
- [ ] D. The host buying/selling at a client's colony: the owner's stock changes (sell a lot of fuel: surplus) and
      the tariff shows in the owner's monthly report, paid at month end (`26d6613`).
- [ ] E. Trading at a player's colony raises reputation with that player's faction, not the independents
      (`26d6613`).
- [ ] F. A fleet kept by a planet after a dialog stays with it through UI clicks, and lets go on a move order
      (`5f43a1c`).

- [x] Hostile NPC fleets (`54219c2`): tested. Copies had no AI (found with the bridge); CopyAI decides for them as
      vanilla's: Hegemony fleets show hostile after the reputation drop, an intercepting patrol fought, the battle
      result and the reputation hit reached the server. Also fine: no re-interception loop after a fight, and
      saving while connected with CopyAI on.
- [x] Fleet copies (`edd4467`, `82c4d6c`): checked with the agent bridge. Around the client every fleet keys by id
      (no duplicates), rosters in the same order, positions within 60; the only fleets missing on the client are the
      server's out of its sensor range. A client's fleet and its copy on the server: 21-29 units apart at 230 units/s
      (the copy ~0.1 s ahead, inside the dead band). (`82c4d6c` went out under the wrong message, "STATUS.md: fleet
      copy fixes to test".)
- [ ] Other players' fleets (`64701e8`): gone from the client's game after disconnecting (and from a save made while
      connected, once loaded: the log says "Removed N other players' fleets saved with this game"). (Seen by normal
      detection: see A above.)
- [ ] Campaign messages (`3119042`): "Joined the server with ..." on joining, "<name> joined/left the game", and
      "Disconnected from the multiplayer server", in the message log at the bottom left.
- [ ] Losing your whole fleet while connected (`988f9b6`): if the game makes a new player fleet, the log says "Our
      fleet is new (...)", and the others and the server keep seeing it (the server log has no repeated "No copy
      of ...'s fleet"). If no such line appears, vanilla keeps the same fleet and nothing was needed.
- [x] Players list (`4866ae0`): the multiplayer window's right side lists who's connected, on the server instance
      and on each client (own name "(you)"), updated as players join, leave and rename.
- [x] No PvP (`7c8341b`): flying into another player's fleet opens "comes alongside ..., another player's" with only
      Leave (both fleets shown), on a client and on the host ("host current game"); fighting an NPC fleet next to
      another player doesn't pull them in ("supporting your forces" / "joining the enemy" never names a player).
- [x] Smoothing (`7c8341b`, `a614c32`, reworked in `2380126`): the user saw the first version work but stutter,
      mostly sideways to a fleet's course. Now copies take their game's velocity (synced with movement) and the
      position is eased in as one 2D vector, a little every frame, no 50-unit threshold (snap past 500 or on a
      location change). Check: other players' and NPC fleets glide without sideways jitter; planets don't jump at
      the 10 s orbit resync.
- [x] Closing the server (or losing the connection) while a client is in a dialog: the client's fleet name loses
      " [PAUSED]" (`a546ef8`); a save that has it loses it on loading.
- [x] Clock (`3ec8821`): checked through the agent bridge (ss_diff status, both read at once): the client was
      4 game minutes off the server, inside the 5-minute dead band (it used to drift up to an hour, then jump).
- [ ] Debris (`9dc2dfb`): a player joining after a battle gets its debris field ("The world has N battle debris
      fields" in their log); a field salvaged while a player was offline is gone for them on rejoining (not
      brought back); with "host current game", the host's battles leave debris for the clients too, and the host
      salvaging it removes it for them. A field received late lasts only what it has left.
- [x] A client jumping: a blue flash where its fleet leaves and arrives, on the host and other clients (`1016605`,
      placed right since `facf7df`).
- [x] Hosting from your own game: a client trading at the host's colony; the server log says the tariff, the
      host's monthly report shows it (`cfff649`).
- [x] Docking at your own colony: "Set this colony's tariff" lists rates; picking one changes the tariff (colony
      screen), the world's copy follows within 5 s, the menu returns to the main options. If the game rejects
      `rules.csv`/`settings.json` at launch, the log says so (`d29d3e8`).
- [ ] A second client trading at your colony: the monthly report shows "Tariffs from other players" under it, paid
      at month end; your log says how much (`50b225c`).
- [x] Reputation change sound plays while connected (once per change; the saved-up one plays again on
      disconnecting) (`85690fe`).
- [x] NPC fleets leave a player alone during a dialog/interception (no swarm afterwards) (`255db92`).
- [x] Fleet kept orbiting a planet after a dialog moves smoothly on the host (not every ~10 s) (`255db92`).
- [x] Renaming the character (console) logs `<old> is now <new>` on the server and renames "<name>'s Fleet".
- [x] Jumping between locations: the client's fleet stays visible on the host, NPC fleets keep arriving (`9282c56`).
- [x] Colony construction catches up after a host fast-forward and the host's copy matches it (`700b459`).

Tested OK: interception dialog + host freeze, debris fields, stations not destroyed by battles, colony trade with
a second client (needs Commerce), player names in the join log, factions/reputation sync, battle results, colony
mirrors (name, accessibility, stability), markets, full-rate locations (smooth fleets away from the host),
hyperspace gravity wells, factions shown in the intel tab only while connected.

## Known limits / ideas (not started)
- Crisis raids in the world (see Architecture), limits: a raid whose source market isn't in the world starts from
  the faction's nearest one; the world tracks hits on colonies only while it's hosting; blockade fleets don't
  hassle players (vanilla aims that at the server game's own player); a colony taken over (Knights) is only the
  world's and the owner's: the other players' games just stop showing it.
- Players' colonies: other players can't attack them at all (online or not), since players can't fight each other;
  capture while the owner is online would need the PvP design too. Not covered: Nexerelin's remote invasions
  launched from a player's own intel screen (no dialog).
- Visitors' prices at a player's colony come from their own game's copy (out of its economy): may differ.
- A later battle adding to an existing debris field doesn't update the others' copies. Salvage done while the
  world's game wasn't hosting, or a player's own offline salvage, isn't shared (only what happens while connected).
- A player's save may have markets the world doesn't (their own single-player pirate bases...): they stay, and
  trading there stays in that game. NPC markets' conditions and industries aren't synced (only owners and sizes).
- Hyperspace slipstreams aren't synced (vanilla regenerates them at random twice a cycle and doesn't expose their
  shape): a player's stay as they were on joining, the world's change. Only the world's fleets look off on them.
- Old-style threats to players' colonies (punitive expeditions over trade, Hegemony AI inspections, pirate base
  raids: vanilla's RaidIntel, not the crises' FleetGroupIntel) don't start while the owner is connected (their
  managers are the world's, which only target its own player); they happen in single player as before.
- Fleets a player's own game makes (missions, bar events) are only in their game: the others don't see them.
- Clock: a client stays up to 5 game minutes (the dead band) behind the server, plus the network delay.
- Players are always neutral to each other and can't fight (PlayerEncounters): PvP would need a real design.
