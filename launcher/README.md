# Multiplayer server launcher

Runs the world in a **second copy of Starsector** that nobody plays (a server instance), so the world never
stops while you're in a dialog or a battle. You play in your normal game and join it like everyone else.
It's a small separate program, because it does things a mod isn't allowed to (start a game, copy saves).

## Use it
1. Start Starsector normally at least once on this PC (the server instance reuses its activation and
   launcher settings).
2. Double-click **Start Server Launcher.bat** (Windows) or `start-server-launcher.sh` (Linux) in this folder.
3. Pick the **world save**, then **START SERVER**. A second Starsector opens in a small window.
4. In that window: **Load Game** and pick the same save. It starts hosting by itself (its title bar says
   "multiplayer server").
5. **START MY GAME** (or start Starsector as usual), load your own save of the same campaign, and in the
   multiplayer window **JOIN** `127.0.0.1` on the same port. Friends join your IP on that port (forward it on
   your router for internet play).

## AI fleets around every player
Vanilla only creates most AI fleets (traders, patrols, base fleets, raiders) near "the player", which on a server
is only the server's own fleet. **MultiplayerAgent.jar** (keep it next to the launcher) fixes that: the launcher
starts the server instance, and your own game via START MY GAME, with it, and it makes vanilla's fleet managers
measure to the **nearest connected player** instead. Nothing on disk is changed; it only applies to games started
from here. Raiders still pick one spawn system at a time.

## Where things are
- The world lives in **`saves-server`** next to your `saves` folder: a copy of the save you picked, so the
  server's autosaves never touch your own save. Later starts reuse it (the world keeps its progress); tick
  "Replace the server's copy" to start the world over from your save.
- The server instance's log is in **`logs-server`**.
- Stop the server by saving and quitting in its window. **FORCE STOP** skips saving.

## Memory
The server instance is a whole second game: it needs about as much memory as your own. The launcher uses your
game's setting (vmparams) unless you change it.
