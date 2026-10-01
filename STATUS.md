# Multiplayer fork: status and handoff

Working branch: `dev` (worktree `D:\Software\starsector_modding\multiplayer\dev`, deployed to the game as the
`mods\dev` link). Build: `javac --release 17` against the game's `starsector-core` jars (+ `jars/libs/compare-utils`),
then `jar cf jars/multiplayer.jar`; the agent jar is `launcher/MultiplayerAgent.jar` (sources in `agent/`).
The jars are locked while a game runs: close both games and the launcher before building.
Pushing over HTTPS sometimes drops; retry, or push one commit at a time
(`for c in $(git rev-list --reverse origin/dev..dev); do git push origin $c:refs/heads/dev || break; done`).

## Needs testing (latest first)
- [ ] NPC fleets leave a player alone during a dialog/interception (no swarm afterwards). Commit `255db92`.
- [ ] Fleet kept orbiting a planet after a dialog moves smoothly on the host (not every ~10 s). Commit `255db92`.
- [ ] Hostile fleets shown as hostile in the client (map colours): their AI's tactical module is wrapped (HostileAwareTactics, commit after 255db92). Also check saving while connected still works.
- [ ] Renaming the character (console) logs `<old> is now <new>` on the server and renames "<name>'s Fleet".
- [ ] Jumping between locations: the client's fleet stays visible on the host, NPC fleets keep arriving.
- [ ] Colony construction catches up after a host fast-forward and the host's copy matches it.

Tested OK: interception dialog + host freeze, debris fields, station not destroyed by battles, colony trade with a
second client (Commerce needed), player names in the join log, factions/reputation sync, battle results.

## Open items
1. **(Done, needs testing) Hostile map colours in the client.** `CampaignFleet.isHostileTo` asks the fleet's AI when it has one;
   `ModularFleetAI.isHostileTo` asks its `TacticalModulePlugin`, which ignores `$cfai_makeHostile` (so
   `ClientScripts.markHostiles` doesn't work). Plan: wrap the tactical module of the client's NPC copies
   (delegate everything, `isHostileTo(playerFleet)` = player faction hostile to the fleet's faction), unwrap before
   saving so the wrapper class never lands in a save.
2. **Reputation sound only plays after disconnecting** (the change itself applies at once). Not traced yet: vanilla
   `CoreReputationPlugin.addAdjustmentMessage` prints to the dialog text panel; the sound may come with a
   notification queued somewhere the connected client holds up.
3. **Later:** tariff income from visitors' trades at a player's colony paid at month end; tariff adjustable by the
   colony's owner.
4. No jump effect on the host when a client changes location (copies are moved straight there, see
   `FleetSerializer` "location": a hyperspace transition could leave them stuck).
