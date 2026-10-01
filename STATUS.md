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

## Architecture (what's where)
- The **server's game is the only authority on the world** (NPC fleets, clock, markets, economy); each **player's
  game is the authority on their own fleet, reputation and colonies**. Clients strip sector scripts while connected
  (`utils/SectorScriptsUtility`, keeps core-engine scripts and `BaseEventIntel` events) and show server-driven copies.
- Protocol version 6 (`server/Server.PROTOCOL_VERSION`). Join: `welcome` (client id) -> client sends `hello` (permanent
  player id from `utils/PlayerIdentity`, name) -> server reserves a player faction (`server/PlayerRegistry`, kept in
  the world save's persistent data) and replies `yourFaction`.
- `server/Server` message dispatch (network threads; game work goes through `gameThreadTasks`). `server/ServerScripts`
  per-frame: fleet updates per client (`updates/FleetSync`, `VisibleFleets`), clock, dialog holds/pins and
  interceptions, dedicated own-fleet hiding (no signature/fuel/supplies), agent data. `server/ServerFactionSync`
  (relations, player faction looks, colonies), `server/ServerMarkets` (market stock and trades), `server/ServerDebris`
  (battle debris: relays players' fields, shares the server game's own, sends them all on joining).
- Client: `client/Client` (connection, `completeJoin`), `client/ClientScripts` (message handling, per-second sends:
  reputation/blueprints/name, colonies, debris, hostile wrapping), `ClientMarkets`, `InteractionOrbit`,
  `HostileAwareTactics`. `utils/PlayerEncounters` (no-combat dialog for players' fleets), `utils/PositionSmoothing`
  (gradual position corrections). `utils/PauseUtility` sends `paused`/`unpaused` (dialog target and positions).
- Shared: `utils/PlayerFactions` (32 player factions `mp_player_N` in `data/world/factions`), `utils/ColonyMirrors`
  (other players' colonies: in the server's economy, display-only elsewhere), `updates/MarketSync`, `BattleSync`,
  `DebrisSync`, `utils/FleetSerializer`/`PersonsSerializer`.
- Agent (`-javaagent`, added by the launcher): `NearestPlayer` (fleet spawning around every player),
  `FullRateLocations` (locations with a player run every frame; patches `CampaignEngine.advance`, 17 calls).
- Colony tariffs: `rulecmd/MP_Tariff` + `data/campaign/rules.csv` + `data/config/settings.json`.

## Needs testing (latest first)
- [ ] Players list (`4866ae0`): the multiplayer window's right side lists who's connected, on the server instance
      and on each client (own name "(you)"), updated as players join, leave and rename.
- [ ] No PvP (`7c8341b`): flying into another player's fleet opens "comes alongside ..., another player's" with only
      Leave (both fleets shown), on a client and on the host ("host current game"); fighting an NPC fleet next to
      another player doesn't pull them in ("supporting your forces" / "joining the enemy" never names a player).
- [ ] Smoothing (`7c8341b`, `a614c32`): other players' and NPC fleets no longer jump when the sync corrects them;
      they glide there in well under a second (over 500 units off they still jump). Jumps between locations still
      move them at once (with the flash). Planets and stations no longer jump at the 10 s orbit resync either.
- [ ] Closing the server (or losing the connection) while a client is in a dialog: the client's fleet name loses
      " [PAUSED]" (`a546ef8`); a save that has it loses it on loading.
- [ ] Clock (`3ec8821`): a client's date no longer jumps an hour every few seconds (the log no longer says "Clock
      set to the server's" every few seconds; it still does after a host fast-forward or a long dialog).
- [ ] Debris (`9dc2dfb`): a player joining after a battle gets its debris field ("The world has N battle debris
      fields" in their log); a field salvaged while a player was offline is gone for them on rejoining (not
      brought back); with "host current game", the host's battles leave debris for the clients too, and the host
      salvaging it removes it for them. A field received late lasts only what it has left.
- [ ] A client jumping: a blue flash where its fleet leaves and arrives, on the host and other clients (`1016605`,
      placed right since `facf7df`).
- [ ] Hosting from your own game: a client trading at the host's colony; the server log says the tariff, the
      host's monthly report shows it (`cfff649`).
- [ ] Docking at your own colony: "Set this colony's tariff" lists rates; picking one changes the tariff (colony
      screen), the world's copy follows within 5 s, the menu returns to the main options. If the game rejects
      `rules.csv`/`settings.json` at launch, the log says so (`d29d3e8`).
- [ ] A second client trading at your colony: the monthly report shows "Tariffs from other players" under it, paid
      at month end; your log says how much (`50b225c`).
- [ ] Reputation change sound plays while connected (once per change; the saved-up one plays again on
      disconnecting) (`85690fe`).
- [ ] Hostile fleets shown as hostile in the client (map colours), and saving while connected still works
      (`97bf239`, HostileAwareTactics wraps the copies' tactical module, unwrapped before saving).
- [ ] NPC fleets leave a player alone during a dialog/interception (no swarm afterwards) (`255db92`).
- [ ] Fleet kept orbiting a planet after a dialog moves smoothly on the host (not every ~10 s) (`255db92`).
- [ ] Renaming the character (console) logs `<old> is now <new>` on the server and renames "<name>'s Fleet".
- [ ] Jumping between locations: the client's fleet stays visible on the host, NPC fleets keep arriving (`9282c56`).
- [ ] Colony construction catches up after a host fast-forward and the host's copy matches it (`700b459`).

Tested OK: interception dialog + host freeze, debris fields, stations not destroyed by battles, colony trade with
a second client (needs Commerce), player names in the join log, factions/reputation sync, battle results, colony
mirrors (name, accessibility, stability), markets, full-rate locations (smooth fleets away from the host),
hyperspace gravity wells, factions shown in the intel tab only while connected.

## Known limits / ideas (not started)
- Crisis raids' fleets don't move while their owner is connected (they go through RouteManager, off on clients).
  Needs a decision: turning RouteManager on in a client would also run every route in its own save (trade fleets,
  patrols...) and duplicate the world's fleets; the raid would have to run in the world (server) or only the raid's
  routes in the client.
- Visitors' prices at a player's colony come from their own game's copy (out of its economy): may differ.
- Salvage loot isn't shared, and a later battle adding to an existing field doesn't update the others' copies.
- Clock: a client stays up to 5 game minutes (the dead band) behind the server, plus the network delay.
- Players are always neutral to each other and can't fight (PlayerEncounters): PvP would need a real design.
