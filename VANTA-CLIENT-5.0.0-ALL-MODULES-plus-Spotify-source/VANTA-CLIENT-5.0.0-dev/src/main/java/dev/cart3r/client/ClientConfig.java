package dev.cart3r.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class ClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("vanta-client.json");
    public static final Map<String, HudPosition> HUD = new LinkedHashMap<>();
    public static final Map<String, Object> originalOptions = new LinkedHashMap<>();
    public static final Map<String, Integer> keybinds = new LinkedHashMap<>();
    public static String theme = "LUNAR";
    public static int hudOpacity = 84;
    public static int espRange = 96;
    public static int storageRange = 24;
    public static boolean espDistance = true;
    public static boolean nametagHealth = false;
    public static String espColorMode = "TEAM";
    public static String tracerOrigin = "BOTTOM";
    /** Spotify Web API user access token with user-read-currently-playing scope. */
    public static String spotifyAccessToken = "";

    private ClientConfig() {}

    public static void load() {
        try {
            if (!Files.exists(FILE)) { defaults(); save(); return; }
            ConfigData data = GSON.fromJson(Files.readString(FILE), ConfigData.class);
            if (data == null) { defaults(); return; }
            Cart3rClient.enabled.clear();
            if (data.enabled != null) Cart3rClient.enabled.addAll(data.enabled);
            keybinds.clear();
            if (data.keybinds != null) keybinds.putAll(data.keybinds);
            HUD.clear();
            if (data.hud != null) HUD.putAll(data.hud);
            theme = data.theme == null ? "LUNAR" : data.theme;
            hudOpacity = Math.max(20, Math.min(100, data.hudOpacity));
            espRange = clamp(data.espRange == 0 ? 96 : data.espRange, 16, 256);
            storageRange = clamp(data.storageRange == 0 ? 24 : data.storageRange, 8, 64);
            espDistance = data.espDistance;
            nametagHealth = data.nametagHealth;
            espColorMode = data.espColorMode == null ? "TEAM" : data.espColorMode;
            tracerOrigin = data.tracerOrigin == null ? "BOTTOM" : data.tracerOrigin;
            spotifyAccessToken = data.spotifyAccessToken == null ? "" : data.spotifyAccessToken;
            ensureHudDefaults();
        } catch (Exception e) {
            defaults();
            System.err.println("[VANTA] Config load failed: " + e.getMessage());
        }
    }

    public static void save() {
        try {
            ensureHudDefaults();
            ConfigData data = new ConfigData();
            data.enabled = new LinkedHashSet<>(Cart3rClient.enabled);
            data.keybinds = new LinkedHashMap<>(keybinds);
            data.hud = new LinkedHashMap<>(HUD);
            data.theme = theme;
            data.hudOpacity = hudOpacity;
            data.espRange = espRange;
            data.storageRange = storageRange;
            data.espDistance = espDistance;
            data.nametagHealth = nametagHealth;
            data.espColorMode = espColorMode;
            data.tracerOrigin = tracerOrigin;
            data.spotifyAccessToken = spotifyAccessToken;
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(data));
        } catch (IOException e) {
            System.err.println("[VANTA] Config save failed: " + e.getMessage());
        }
    }

    public static void defaults() {
        Cart3rClient.enabled.clear();
        keybinds.clear();
        HUD.clear();
        theme = "LUNAR";
        hudOpacity = 84;
        espRange = 96;
        storageRange = 24;
        espDistance = true;
        nametagHealth = false;
        espColorMode = "TEAM";
        tracerOrigin = "BOTTOM";
        spotifyAccessToken = "";
        ensureHudDefaults();
    }

    public static void ensureHudDefaults() {
        HUD.putIfAbsent("Coordinates", new HudPosition(10, 10));
        HUD.putIfAbsent("Keystrokes", new HudPosition(10, 42));
        HUD.putIfAbsent("Armor HUD", new HudPosition(10, 105));
        HUD.putIfAbsent("Health HUD", new HudPosition(10, 130));
        HUD.putIfAbsent("Reach Display", new HudPosition(10, 145));
        HUD.putIfAbsent("Toggle Sprint", new HudPosition(10, 170));
        HUD.putIfAbsent("FPS Boost", new HudPosition(10, 192));
        HUD.putIfAbsent("X-Ray", new HudPosition(10, 236));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class HudPosition {
        public int x, y;
        public HudPosition(int x, int y) { this.x = x; this.y = y; }
    }

    private static final class ConfigData {
        Set<String> enabled = new LinkedHashSet<>();
        Map<String, Integer> keybinds = new LinkedHashMap<>();
        Map<String, HudPosition> hud = new LinkedHashMap<>();
        String theme = "LUNAR";
        int hudOpacity = 84;
        int espRange = 96;
        int storageRange = 24;
        boolean espDistance = true;
        boolean nametagHealth = false;
        String espColorMode = "TEAM";
        String tracerOrigin = "BOTTOM";
        String spotifyAccessToken = "";
    }
}
