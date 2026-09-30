@echo off
rem Starts the multiplayer server launcher with the game's own Java (this folder is mods\<mod>\launcher).
start "" "%~dp0..\..\..\jre\bin\javaw.exe" -jar "%~dp0MultiplayerLauncher.jar"
