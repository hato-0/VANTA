# VANTA CLIENT 5.0.0 — combined development source

Minecraft Java 1.21.1 / Fabric / Java 21.

This ZIP combines the latest source revisions into one project. It is source code only; it is **not** a compiled or tested JAR.

## Included modules in this source
- Fullbright, Keystrokes, Coordinates
- Player ESP, Entity ESP, Tracers, Nametags, Storage ESP, Light Debug, X-Ray
- Reach Display (display only; does not extend attack reach)
- FPS Boost, Toggle Sprint, Armor HUD, Health HUD, Auto Tool Swap
- Auto Totem
- Freecam (client-side detached camera implementation; requires in-game validation)
- Boat Fly
- Speed Mine (client-side mining behavior; server rules still apply)
- Hitbox (attempts to toggle Minecraft's built-in hitbox renderer)
- Zoom
- Auto Armor
- Auto Eat
- Flight, Speed, Step
- Animation Speed (shorter local hand-swing duration)
- Spotify Overlay (official Spotify currently-playing API; requires a user access token)

## Settings and controls
Open the menu with Right Shift. Left-click toggles a module; right-click selects a module for its settings. Keybinds and supported ESP/HUD settings are saved in `.minecraft/config/vanta-client.json`.

## Important limitations
- This package has not been compiled or tested in Minecraft 1.21.1. API/mapping compatibility and runtime behavior are unverified.
- Some modules are experimental and may need fixes after a real Gradle build and in-game test.
- Spotify Overlay requires a Spotify Web API user access token with the `user-read-currently-playing` scope. Put it in the `spotifyAccessToken` field of `.minecraft/config/vanta-client.json`; tokens expire and must be renewed manually.
- Authorized single-player duplication testing and several other wishlist items are not included.
- Reach Display only reports distance; it does not provide extended reach.
- Flight/Speed/Boat Fly and mining behavior may be corrected or blocked by multiplayer servers. No server-side bypass is included.

## Build
Use JDK 21 and a compatible Gradle 8.x installation, then run `gradle build`. The mod JAR, if the build succeeds, will be in `build/libs/` (choose the regular mod JAR, not a sources JAR).

## Spotify Overlay setup
1. Enable `Spotify Overlay` in the VANTA menu once.
2. Obtain a Spotify user access token with the `user-read-currently-playing` scope through Spotify's official authorization flow.
3. Open `.minecraft/config/vanta-client.json` and set `spotifyAccessToken` to that token.
4. Restart the client or reload the config by restarting Minecraft. The overlay polls about every five seconds while enabled.

The token is stored locally in the Minecraft config file. Do not share that file or the token with anyone. Spotify access tokens expire; this implementation does not refresh them automatically.
