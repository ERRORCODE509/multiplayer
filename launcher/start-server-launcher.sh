#!/bin/sh
# Starts the multiplayer server launcher with the game's own Java (this folder is mods/<mod>/launcher).
cd "$(dirname "$0")" && exec ../../../jre_linux/bin/java -jar MultiplayerLauncher.jar
