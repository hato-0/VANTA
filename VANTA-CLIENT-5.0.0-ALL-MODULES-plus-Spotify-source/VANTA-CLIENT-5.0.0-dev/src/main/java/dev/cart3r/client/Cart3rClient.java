package dev.cart3r.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.OtherClientPlayerEntity;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.UUID;

public class Cart3rClient implements ClientModInitializer {
    public static final String[] MODULES = {
            "Fullbright", "Keystrokes", "Coordinates", "Player ESP", "Entity ESP",
            "Tracers", "Nametags", "Storage ESP", "Light Debug", "X-Ray",
            "Reach Display", "FPS Boost", "Toggle Sprint", "Armor HUD", "Health HUD", "Auto Tool Swap",
            "Auto Totem", "Freecam", "Boat Fly", "Speed Mine", "Animation Speed", "Hitbox",
            "Zoom", "Auto Armor", "Auto Eat", "Flight", "Speed", "Step", "Spotify Overlay"
    };
    public static final Set<String> enabled = new LinkedHashSet<>();
    private static boolean rightShiftWasDown;
    private static final Set<Integer> heldModuleKeys = new HashSet<>();
    private static int tickCounter;
    private static int lastEspUpdate = -100;
    private static int lastStorageUpdate = -100;
    private static List<Entity> cachedEspEntities = List.of();
    private static List<StorageMarker> cachedStorage = List.of();
    private record StorageMarker(BlockPos pos, String label, int light) {}
    private static int autoSwapOriginalSlot = -1;
    private static int autoSwapLastSlot = -1;
    private static boolean autoSwapWasActive;
    private static int lastTotemSwapTick = -1000;
    private static OtherClientPlayerEntity freecamEntity;
    private static boolean freecamWasEnabled;
    private static Field miningCooldownField;
    private static boolean miningCooldownLookupDone;
    private static Field hitboxField;
    private static boolean hitboxFieldLookupDone;
    private static boolean hitboxWasEnabled;
    private static Integer originalFov;
    private static int lastAutoArmorTick = -1000;
    private static int lastAutoEatTick = -1000;
    private static int autoEatOriginalSlot = -1;
    private static int autoEatFoodSlot = -1;
    private static boolean originalHitboxState;
    private static final HttpClient SPOTIFY_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4)).build();
    private static final AtomicBoolean spotifyRequestRunning = new AtomicBoolean(false);
    private static volatile String spotifyTrackLine = "Spotify: waiting for playback";
    private static int lastSpotifyPoll = -1000;
    private static List<BlockPos> nearbyOres = List.of();
    private static final Block[] ORES = {
            Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.ANCIENT_DEBRIS, Blocks.REDSTONE_ORE,
            Blocks.LAPIS_ORE, Blocks.COPPER_ORE
    };

    @Override
    public void onInitializeClient() {
        ClientConfig.load();
    }

    /** Called from a client tick mixin; no Fabric API event dependency. */
    public static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) return;

        boolean shiftDown = GLFW.glfwGetKey(mc.getWindow().getHandle(), GLFW.GLFW_KEY_RIGHT_SHIFT)
                == GLFW.GLFW_PRESS;
        if (shiftDown && !rightShiftWasDown) mc.setScreen(new Cart3rScreen());
        rightShiftWasDown = shiftDown;

        // Module keybinds are edge-triggered and ignored while a screen is open.
        if (mc.currentScreen == null) {
            for (var entry : ClientConfig.keybinds.entrySet()) {
                int key = entry.getValue();
                if (key < 0) continue;
                boolean down = GLFW.glfwGetKey(mc.getWindow().getHandle(), key) == GLFW.GLFW_PRESS;
                if (down && heldModuleKeys.add(key)) {
                    String module = entry.getKey();
                    if (!enabled.add(module)) enabled.remove(module);
                    ClientConfig.save();
                } else if (!down) heldModuleKeys.remove(key);
            }
        } else {
            heldModuleKeys.clear();
        }

        if (mc.player == null || mc.world == null) {
            freecamEntity = null;
            freecamWasEnabled = false;
            return;
        }
        tickCounter++;
        updateFreecam(mc);
        updateBoatFly(mc);
        updateSpeedMine(mc);
        updateHitboxes(mc);
        updateZoom(mc);
        updateAutoArmor(mc);
        updateAutoEat(mc);
        updateFlight(mc);
        updateSpeed(mc);
        updateStep(mc);
        updateSpotifyOverlay();

        if (enabled.contains("Fullbright")) setOption(mc, "gamma", 12.0);
        else restoreOption(mc, "gamma");

        if (enabled.contains("FPS Boost")) {
            if (tickCounter % 100 == 0) {
                setEnumOption(mc, "particles", "MINIMAL");
                setOption(mc, "entityShadows", false);
            }
        } else {
            restoreOption(mc, "particles");
            restoreOption(mc, "entityShadows");
        }

        if (enabled.contains("Toggle Sprint")) mc.player.setSprinting(true);

        updateAutoToolSwap(mc);
        updateAutoTotem(mc);

        // Refresh ESP targets periodically; HUD rendering reads cached results.
        if (tickCounter - lastEspUpdate >= 5) {
            if (enabled.contains("Player ESP") || enabled.contains("Entity ESP")
                    || enabled.contains("Tracers") || enabled.contains("Nametags")) {
                List<Entity> visible = new ArrayList<>();
                double maxSq = ClientConfig.espRange * (double) ClientConfig.espRange;
                for (Entity entity : mc.world.getEntities()) {
                    if (entity == mc.player || !entity.isAlive()) continue;
                    if (mc.player.squaredDistanceTo(entity) > maxSq) continue;
                    boolean player = entity instanceof net.minecraft.entity.player.PlayerEntity;
                    if ((player && enabled.contains("Player ESP"))
                            || (!player && enabled.contains("Entity ESP"))) visible.add(entity);
                }
                cachedEspEntities = List.copyOf(visible);
            } else cachedEspEntities = List.of();
            lastEspUpdate = tickCounter;
        }

        if (tickCounter - lastStorageUpdate >= 20) {
            if (enabled.contains("Storage ESP")) {
                List<StorageMarker> markers = new ArrayList<>();
                BlockPos center = mc.player.getBlockPos();
                int storageRange = ClientConfig.storageRange;
                for (BlockPos pos : BlockPos.iterateOutwards(center, storageRange, 12, storageRange)) {
                    Block block = mc.world.getBlockState(pos).getBlock();
                    String label = null;
                    if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) label = "Chest";
                    else if (block == Blocks.BARREL) label = "Barrel";
                    else if (block == Blocks.ENDER_CHEST) label = "Ender Chest";
                    else if (block == Blocks.SPAWNER) label = "Spawner";
                    else if (block == Blocks.SHULKER_BOX || block == Blocks.WHITE_SHULKER_BOX
                            || block == Blocks.ORANGE_SHULKER_BOX || block == Blocks.MAGENTA_SHULKER_BOX
                            || block == Blocks.LIGHT_BLUE_SHULKER_BOX || block == Blocks.YELLOW_SHULKER_BOX
                            || block == Blocks.LIME_SHULKER_BOX || block == Blocks.PINK_SHULKER_BOX
                            || block == Blocks.GRAY_SHULKER_BOX || block == Blocks.LIGHT_GRAY_SHULKER_BOX
                            || block == Blocks.CYAN_SHULKER_BOX || block == Blocks.PURPLE_SHULKER_BOX
                            || block == Blocks.BLUE_SHULKER_BOX || block == Blocks.BROWN_SHULKER_BOX
                            || block == Blocks.GREEN_SHULKER_BOX || block == Blocks.RED_SHULKER_BOX
                            || block == Blocks.BLACK_SHULKER_BOX) label = "Shulker";
                    if (label != null) markers.add(new StorageMarker(pos.toImmutable(), label,
                            mc.world.getLightLevel(net.minecraft.world.LightType.BLOCK, pos)));
                }
                markers.sort(Comparator.comparingDouble(m -> m.pos().getSquaredDistance(center)));
                cachedStorage = List.copyOf(markers.subList(0, Math.min(12, markers.size())));
            } else cachedStorage = List.of();
            lastStorageUpdate = tickCounter;
        }

        // Bounded, cached scan: 13x9x13 = 1,521 block checks, once every 100 ticks.
        if (enabled.contains("X-Ray") && tickCounter % 100 == 0) {
            scanOres(mc.world, mc.player.getBlockPos());
        } else if (!enabled.contains("X-Ray")) {
            nearbyOres = List.of();
        }
    }


    /**
     * Client-side detached camera. The real player remains at the original position;
     * the temporary camera entity is never added to the world or sent to the server.
     */
    private static void updateFreecam(MinecraftClient mc) {
        boolean active = enabled.contains("Freecam");
        if (active && !freecamWasEnabled) {
            freecamEntity = new OtherClientPlayerEntity(mc.world,
                    new GameProfile(UUID.randomUUID(), "VANTA-Camera"));
            freecamEntity.copyPositionAndRotation(mc.player);
            freecamEntity.setNoGravity(true);
            freecamEntity.noClip = true;
            mc.setCameraEntity(freecamEntity);
            freecamWasEnabled = true;
        }
        if (active && freecamEntity != null) {
            freecamEntity.noClip = true;
            freecamEntity.setNoGravity(true);
            freecamEntity.setYaw(mc.player.getYaw());
            freecamEntity.setPitch(mc.player.getPitch());
            double forward = 0.0, strafe = 0.0, vertical = 0.0;
            if (mc.options.forwardKey.isPressed()) forward += 1.0;
            if (mc.options.backKey.isPressed()) forward -= 1.0;
            if (mc.options.leftKey.isPressed()) strafe += 1.0;
            if (mc.options.rightKey.isPressed()) strafe -= 1.0;
            if (mc.options.jumpKey.isPressed()) vertical += 1.0;
            if (mc.options.sneakKey.isPressed()) vertical -= 1.0;
            double speed = mc.options.sprintKey.isPressed() ? 1.25 : 0.45;
            double yaw = Math.toRadians(freecamEntity.getYaw());
            double dx = (strafe * Math.cos(yaw) - forward * Math.sin(yaw)) * speed;
            double dz = (forward * Math.cos(yaw) + strafe * Math.sin(yaw)) * speed;
            freecamEntity.setPosition(freecamEntity.getX() + dx,
                    freecamEntity.getY() + vertical * speed, freecamEntity.getZ() + dz);
            freecamEntity.setVelocity(0, 0, 0);
        } else if (!active && freecamWasEnabled) {
            mc.setCameraEntity(mc.player);
            freecamEntity = null;
            freecamWasEnabled = false;
        }
    }

    /** Client-side boat movement helper. Server rules may still correct/reject the motion. */
    private static void updateBoatFly(MinecraftClient mc) {
        if (!enabled.contains("Boat Fly") || mc.player == null || mc.player.getVehicle() == null
                || !(mc.player.getVehicle() instanceof net.minecraft.entity.vehicle.BoatEntity boat)) return;
        boat.setNoGravity(true);
        double forward = 0.0, strafe = 0.0, vertical = 0.0;
        if (mc.options.forwardKey.isPressed()) forward += 1.0;
        if (mc.options.backKey.isPressed()) forward -= 1.0;
        if (mc.options.leftKey.isPressed()) strafe += 1.0;
        if (mc.options.rightKey.isPressed()) strafe -= 1.0;
        if (mc.options.jumpKey.isPressed()) vertical += 0.55;
        if (mc.options.sneakKey.isPressed()) vertical -= 0.55;
        double yaw = Math.toRadians(mc.player.getYaw());
        double speed = mc.options.sprintKey.isPressed() ? 0.85 : 0.42;
        double dx = (strafe * Math.cos(yaw) - forward * Math.sin(yaw)) * speed;
        double dz = (forward * Math.cos(yaw) + strafe * Math.sin(yaw)) * speed;
        boat.setVelocity(dx, vertical, dz);
        boat.setNoGravity(false);
    }

    /** Reduces the local delay between block-breaking updates; actual mining speed remains server-authoritative. */
    private static void updateSpeedMine(MinecraftClient mc) {
        if (!enabled.contains("Speed Mine") || mc.player == null || mc.interactionManager == null
                || mc.currentScreen != null || !mc.options.attackKey.isPressed()
                || mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return;
        try {
            if (!miningCooldownLookupDone) {
                miningCooldownField = mc.interactionManager.getClass().getDeclaredField("blockBreakingCooldown");
                miningCooldownField.setAccessible(true);
                miningCooldownLookupDone = true;
            }
            if (miningCooldownField != null) miningCooldownField.setInt(mc.interactionManager, 0);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Keep the client stable if mappings or access rules differ.
            miningCooldownField = null;
            miningCooldownLookupDone = true;
        }
    }

    /** Toggles Minecraft's built-in entity hitbox renderer while preserving its prior state. */
    private static void updateHitboxes(MinecraftClient mc) {
        boolean active = enabled.contains("Hitbox");
        try {
            Object dispatcher = mc.getEntityRenderDispatcher();
            if (!hitboxFieldLookupDone) {
                for (Field field : dispatcher.getClass().getDeclaredFields()) {
                    if (field.getType() == boolean.class && field.getName().toLowerCase(Locale.ROOT).contains("hitbox")) {
                        field.setAccessible(true);
                        hitboxField = field;
                        break;
                    }
                }
                hitboxFieldLookupDone = true;
            }
            if (hitboxField == null) return;
            if (active && !hitboxWasEnabled) {
                originalHitboxState = hitboxField.getBoolean(dispatcher);
                hitboxField.setBoolean(dispatcher, true);
                hitboxWasEnabled = true;
            } else if (!active && hitboxWasEnabled) {
                hitboxField.setBoolean(dispatcher, originalHitboxState);
                hitboxWasEnabled = false;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            hitboxField = null;
            hitboxFieldLookupDone = true;
        }
    }

    /** Applies a conservative fixed FOV while Zoom is enabled and restores the user's setting. */
    private static void updateZoom(MinecraftClient mc) {
        try {
            var fov = mc.options.getFov();
            if (enabled.contains("Zoom")) {
                if (originalFov == null) originalFov = fov.getValue();
                if (fov.getValue() != 30) fov.setValue(30);
            } else if (originalFov != null) {
                fov.setValue(originalFov);
                originalFov = null;
            }
        } catch (RuntimeException ignored) {
            // Leave the game option untouched if the client implementation differs.
        }
    }

    /** Equips armor from the hotbar into empty armor slots using normal inventory swaps. */
    private static void updateAutoArmor(MinecraftClient mc) {
        if (!enabled.contains("Auto Armor") || mc.player == null || mc.interactionManager == null
                || mc.currentScreen != null || tickCounter - lastAutoArmorTick < 40) return;
        var inventory = mc.player.getInventory();
        for (int hotbar = 0; hotbar < 9; hotbar++) {
            ItemStack stack = inventory.getStack(hotbar);
            if (!(stack.getItem() instanceof net.minecraft.item.ArmorItem armor)) continue;
            int entitySlot = armor.getSlotType().getEntitySlotId();
            int armorIndex = 3 - entitySlot;
            if (entitySlot < 0 || entitySlot > 3 || !inventory.getArmorStack(armorIndex).isEmpty()) continue;
            int handlerSlot = 5 + armorIndex;
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, handlerSlot, hotbar,
                    net.minecraft.screen.slot.SlotActionType.SWAP, mc.player);
            lastAutoArmorTick = tickCounter;
            return;
        }
    }

    /** Uses food already present in the hotbar when hunger is low; no inventory or server bypass. */
    private static void updateAutoEat(MinecraftClient mc) {
        if (!enabled.contains("Auto Eat") || mc.player == null || mc.interactionManager == null
                || mc.currentScreen != null) {
            if (autoEatOriginalSlot >= 0 && mc.player != null && autoEatFoodSlot >= 0
                    && mc.player.getInventory().selectedSlot == autoEatFoodSlot) {
                mc.player.getInventory().selectedSlot = autoEatOriginalSlot;
            }
            autoEatOriginalSlot = -1;
            autoEatFoodSlot = -1;
            return;
        }
        if (mc.player.getHungerManager().getFoodLevel() >= 18) {
            if (autoEatOriginalSlot >= 0 && autoEatFoodSlot >= 0
                    && mc.player.getInventory().selectedSlot == autoEatFoodSlot) {
                mc.player.getInventory().selectedSlot = autoEatOriginalSlot;
            }
            autoEatOriginalSlot = -1;
            autoEatFoodSlot = -1;
            return;
        }
        if (mc.player.getHungerManager().getFoodLevel() > 14 || tickCounter - lastAutoEatTick < 30) return;
        var inventory = mc.player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || stack.get(net.minecraft.component.DataComponentTypes.FOOD) == null) continue;
            if (autoEatOriginalSlot < 0) autoEatOriginalSlot = inventory.selectedSlot;
            inventory.selectedSlot = slot;
            autoEatFoodSlot = slot;
            mc.interactionManager.interactItem(mc.player, net.minecraft.util.Hand.MAIN_HAND);
            lastAutoEatTick = tickCounter;
            return;
        }
    }

    /** Moves a totem into the offhand when Auto Totem is enabled and the offhand is empty.
     * Uses the normal inventory SWAP action; it does not generate items or bypass server rules.
     */
    private static void updateFlight(MinecraftClient mc) {
        if (!enabled.contains("Flight") || mc.player == null || mc.currentScreen != null
                || mc.player.getVehicle() != null || enabled.contains("Freecam")) return;
        var player = mc.player;
        player.setNoGravity(true);
        double forward = (mc.options.forwardKey.isPressed() ? 1.0 : 0.0)
                - (mc.options.backKey.isPressed() ? 1.0 : 0.0);
        double strafe = (mc.options.leftKey.isPressed() ? 1.0 : 0.0)
                - (mc.options.rightKey.isPressed() ? 1.0 : 0.0);
        double vertical = (mc.options.jumpKey.isPressed() ? 1.0 : 0.0)
                - (mc.options.sneakKey.isPressed() ? 1.0 : 0.0);
        double speed = mc.options.sprintKey.isPressed() ? 0.72 : 0.36;
        double yaw = Math.toRadians(player.getYaw());
        double dx = (strafe * Math.cos(yaw) - forward * Math.sin(yaw)) * speed;
        double dz = (forward * Math.cos(yaw) + strafe * Math.sin(yaw)) * speed;
        player.setVelocity(dx, vertical * speed, dz);
        player.velocityModified = true;
    }

    private static void updateSpeed(MinecraftClient mc) {
        if (!enabled.contains("Speed") || mc.player == null || mc.currentScreen != null
                || mc.player.getVehicle() != null || enabled.contains("Flight")) return;
        var player = mc.player;
        double forward = (mc.options.forwardKey.isPressed() ? 1.0 : 0.0)
                - (mc.options.backKey.isPressed() ? 1.0 : 0.0);
        double strafe = (mc.options.leftKey.isPressed() ? 1.0 : 0.0)
                - (mc.options.rightKey.isPressed() ? 1.0 : 0.0);
        if (forward == 0.0 && strafe == 0.0) return;
        double yaw = Math.toRadians(player.getYaw());
        double multiplier = mc.options.sprintKey.isPressed() ? 1.18 : 1.12;
        var velocity = player.getVelocity();
        double vx = velocity.x * multiplier;
        double vz = velocity.z * multiplier;
        double max = mc.options.sprintKey.isPressed() ? 0.42 : 0.32;
        double horizontal = Math.sqrt(vx * vx + vz * vz);
        if (horizontal > max) {
            vx = vx / horizontal * max;
            vz = vz / horizontal * max;
        }
        player.setVelocity(vx, velocity.y, vz);
    }

    private static void updateStep(MinecraftClient mc) {
        if (!enabled.contains("Step") || mc.player == null || mc.currentScreen != null
                || !mc.player.isOnGround() || !mc.player.horizontalCollision
                || mc.player.getVehicle() != null) return;
        boolean moving = mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
                || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed();
        if (moving && !mc.player.isJumping()) mc.player.jump();
    }

    /** Polls Spotify's official currently-playing endpoint. Configure a user token in vanta-client.json. */
    private static void updateSpotifyOverlay() {
        if (!enabled.contains("Spotify Overlay") || ClientConfig.spotifyAccessToken == null
                || ClientConfig.spotifyAccessToken.isBlank() || tickCounter - lastSpotifyPoll < 100
                || !spotifyRequestRunning.compareAndSet(false, true)) return;
        lastSpotifyPoll = tickCounter;
        String token = ClientConfig.spotifyAccessToken.trim();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.spotify.com/v1/me/player/currently-playing"))
                .timeout(Duration.ofSeconds(6))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        SPOTIFY_HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    try {
                        if (error != null) {
                            spotifyTrackLine = "Spotify: connection error";
                        } else if (response.statusCode() == 204) {
                            spotifyTrackLine = "Spotify: nothing playing";
                        } else if (response.statusCode() == 401) {
                            spotifyTrackLine = "Spotify: token expired / invalid";
                        } else if (response.statusCode() == 200) {
                            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                            JsonObject item = root.has("item") && root.get("item").isJsonObject()
                                    ? root.getAsJsonObject("item") : null;
                            if (item == null) {
                                spotifyTrackLine = "Spotify: nothing playing";
                            } else {
                                String title = item.has("name") ? item.get("name").getAsString() : "Unknown track";
                                String artist = "Unknown artist";
                                if (item.has("artists") && item.getAsJsonArray("artists").size() > 0) {
                                    List<String> names = new ArrayList<>();
                                    item.getAsJsonArray("artists").forEach(a -> {
                                        if (a.isJsonObject() && a.getAsJsonObject().has("name"))
                                            names.add(a.getAsJsonObject().get("name").getAsString());
                                    });
                                    if (!names.isEmpty()) artist = String.join(", ", names);
                                }
                                spotifyTrackLine = "Spotify  " + title + " - " + artist;
                            }
                        } else {
                            spotifyTrackLine = "Spotify: API status " + response.statusCode();
                        }
                    } catch (RuntimeException parseError) {
                        spotifyTrackLine = "Spotify: unable to read track";
                    } finally {
                        spotifyRequestRunning.set(false);
                    }
                });
    }

    private static void updateAutoTotem(MinecraftClient mc) {
        if (!enabled.contains("Auto Totem") || mc.player == null || mc.interactionManager == null
                || mc.currentScreen != null || tickCounter - lastTotemSwapTick < 10) return;
        ItemStack offhand = mc.player.getOffHandStack();
        if (!offhand.isEmpty() && offhand.getItem() == net.minecraft.item.Items.TOTEM_OF_UNDYING) return;
        var inventory = mc.player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || stack.getItem() != net.minecraft.item.Items.TOTEM_OF_UNDYING) continue;
            // PlayerScreenHandler maps main inventory slots to 9..35 and hotbar to 36..44.
            int handlerSlot = slot < 9 ? 36 + slot : slot;
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, handlerSlot, 40,
                    net.minecraft.screen.slot.SlotActionType.SWAP, mc.player);
            lastTotemSwapTick = tickCounter;
            return;
        }
    }

    /** Selects the fastest suitable tool in the hotbar while the player holds attack.
     * Restores the previously selected slot when mining stops, unless the player
     * manually changed away from the slot selected by this module.
     */
    private static void updateAutoToolSwap(MinecraftClient mc) {
        boolean shouldRun = enabled.contains("Auto Tool Swap")
                && mc.currentScreen == null
                && mc.options.attackKey.isPressed()
                && mc.crosshairTarget != null
                && mc.crosshairTarget.getType() == HitResult.Type.BLOCK;

        if (!shouldRun) {
            if (autoSwapWasActive && autoSwapLastSlot >= 0
                    && mc.player.getInventory().selectedSlot == autoSwapLastSlot
                    && autoSwapOriginalSlot >= 0) {
                mc.player.getInventory().selectedSlot = autoSwapOriginalSlot;
            }
            autoSwapOriginalSlot = -1;
            autoSwapLastSlot = -1;
            autoSwapWasActive = false;
            return;
        }

        var inventory = mc.player.getInventory();
        int selected = inventory.selectedSlot;
        if (!autoSwapWasActive) {
            autoSwapOriginalSlot = selected;
            autoSwapWasActive = true;
        } else if (autoSwapLastSlot >= 0 && selected != autoSwapLastSlot) {
            // Respect a manual hotbar change made by the player.
            autoSwapOriginalSlot = selected;
        }

        BlockPos targetPos = ((net.minecraft.util.hit.BlockHitResult) mc.crosshairTarget).getBlockPos();
        BlockState state = mc.world.getBlockState(targetPos);
        if (state.isAir()) return;

        int bestSlot = -1;
        float bestSpeed = -1.0f;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isSuitableFor(state)) continue;
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }

        if (bestSlot >= 0 && bestSlot != selected) {
            inventory.selectedSlot = bestSlot;
        }
        if (bestSlot >= 0) autoSwapLastSlot = bestSlot;
    }

    private static void scanOres(World world, BlockPos center) {
        List<BlockPos> found = new ArrayList<>();
        for (int x = -6; x <= 6; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -6; z <= 6; z++) {
                    BlockPos pos = center.add(x, y, z);
                    Block block = world.getBlockState(pos).getBlock();
                    for (Block ore : ORES) {
                        if (block == ore) {
                            found.add(pos.toImmutable());
                            break;
                        }
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
        nearbyOres = List.copyOf(found.subList(0, Math.min(6, found.size())));
    }

    public static int accentColor() {
        return switch (ClientConfig.theme) {
            case "CRIMSON" -> 0xFFFF5364;
            case "EMERALD" -> 0xFF42D6A4;
            case "VIOLET" -> 0xFFAA82FF;
            case "AMBER" -> 0xFFFFB84D;
            default -> 0xFF61A9FF;
        };
    }

    private static int panelColor() {
        int alpha = Math.max(20, Math.min(100, ClientConfig.hudOpacity)) * 255 / 100;
        return (alpha << 24) | 0x00161D27;
    }

    private static int accentWithAlpha() { return accentColor(); }

    /** Called by the HUD mixin after vanilla HUD rendering. */
    public static void renderHud(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        ClientConfig.ensureHudDefaults();

        if (enabled.contains("Coordinates")) {
            var p = ClientConfig.HUD.get("Coordinates");
            panel(ctx, mc, String.format(Locale.ROOT, "XYZ  %d / %d / %d",
                    MathHelper.floor(mc.player.getX()), MathHelper.floor(mc.player.getY()),
                    MathHelper.floor(mc.player.getZ())), p.x, p.y);
        }
        if (enabled.contains("Keystrokes")) {
            var p = ClientConfig.HUD.get("Keystrokes");
            long win = mc.getWindow().getHandle();
            key(ctx, mc, "W", p.x + 22, p.y, down(win, GLFW.GLFW_KEY_W));
            key(ctx, mc, "A", p.x, p.y + 22, down(win, GLFW.GLFW_KEY_A));
            key(ctx, mc, "S", p.x + 22, p.y + 22, down(win, GLFW.GLFW_KEY_S));
            key(ctx, mc, "D", p.x + 44, p.y + 22, down(win, GLFW.GLFW_KEY_D));
            key(ctx, mc, "SPC", p.x + 4, p.y + 44, down(win, GLFW.GLFW_KEY_SPACE));
        }
        if (enabled.contains("Reach Display") && mc.crosshairTarget != null
                && mc.crosshairTarget.getType() != HitResult.Type.MISS) {
            var p = ClientConfig.HUD.get("Reach Display");
            double distance = mc.player.getEyePos().distanceTo(mc.crosshairTarget.getPos());
            panel(ctx, mc, String.format(Locale.ROOT, "Reach  %.2fm", distance), p.x, p.y);
        }
        if (enabled.contains("Armor HUD")) {
            var p = ClientConfig.HUD.get("Armor HUD");
            int x = p.x;
            for (ItemStack stack : mc.player.getArmorItems()) {
                if (!stack.isEmpty()) {
                    ctx.fill(x - 2, p.y - 2, x + 18, p.y + 18, panelColor());
                    ctx.drawItem(stack, x, p.y);
                }
                x += 22;
            }
        }
        if (enabled.contains("Health HUD")) {
            var p = ClientConfig.HUD.get("Health HUD");
            float health = mc.player.getHealth();
            float maxHealth = mc.player.getMaxHealth();
            float absorption = mc.player.getAbsorptionAmount();
            int barWidth = 104;
            int filled = Math.round(barWidth * MathHelper.clamp(health / Math.max(1.0f, maxHealth), 0.0f, 1.0f));
            ctx.fill(p.x, p.y, p.x + barWidth + 36, p.y + 22, panelColor());
            ctx.drawTextWithShadow(mc.textRenderer, "♥", p.x + 6, p.y + 6, 0xFFFF647C);
            ctx.drawTextWithShadow(mc.textRenderer,
                    String.format(Locale.ROOT, "%.1f%s", health, absorption > 0 ? " +" + Math.round(absorption) : ""),
                    p.x + 20, p.y + 6, 0xFFEAF2FF);
            ctx.fill(p.x + 6, p.y + 17, p.x + 6 + barWidth, p.y + 19, 0xFF394451);
            ctx.fill(p.x + 6, p.y + 17, p.x + 6 + filled, p.y + 19, accentColor());
        }
        if (enabled.contains("Toggle Sprint")) {
            var p = ClientConfig.HUD.get("Toggle Sprint");
            panel(ctx, mc, "SPRINT  ON", p.x, p.y);
        }
        if (enabled.contains("FPS Boost")) {
            var p = ClientConfig.HUD.get("FPS Boost");
            panel(ctx, mc, "FPS OPTIMIZER", p.x, p.y);
        }
        if (enabled.contains("Spotify Overlay")) {
            var p = ClientConfig.HUD.computeIfAbsent("Spotify Overlay", k -> new ClientConfig.HudPosition(10, 265));
            panel(ctx, mc, spotifyTrackLine, p.x, p.y);
        }
        renderEspOverlay(ctx, mc);
        renderStorageOverlay(ctx, mc);
        if (enabled.contains("Light Debug")) {
            BlockPos pos = mc.player.getBlockPos();
            int blockLight = mc.world.getLightLevel(net.minecraft.world.LightType.BLOCK, pos);
            int skyLight = mc.world.getLightLevel(net.minecraft.world.LightType.SKY, pos);
            panel(ctx, mc, "Light  Block " + blockLight + " / Sky " + skyLight,
                    10, mc.getWindow().getScaledHeight() - 42);
        }
        if (enabled.contains("X-Ray")) {
            var p = ClientConfig.HUD.get("X-Ray");
            panel(ctx, mc, "ORE RADAR  " + nearbyOres.size() + " FOUND", p.x, p.y);
        }
    }

    private record ScreenPoint(float x, float y) {}

    private static void renderEspOverlay(DrawContext ctx, MinecraftClient mc) {
        if (cachedEspEntities.isEmpty()) return;
        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        int centerX = sw / 2, row = 0;
        for (Entity entity : cachedEspEntities) {
            double distance = Math.sqrt(mc.player.squaredDistanceTo(entity));
            String name = entity.getName().getString();
            String label = ClientConfig.espDistance
                    ? name + "  " + String.format(Locale.ROOT, "%.1fm", distance) : name;
            int color = switch (ClientConfig.espColorMode) {
                case "RAINBOW" -> 0xFFB58CFF;
                case "GREEN" -> 0xFF72E0A1;
                case "RED" -> 0xFFFF7885;
                default -> entity instanceof net.minecraft.entity.player.PlayerEntity ? 0xFFFF6B78 : 0xFF71C7FF;
            };
            if (enabled.contains("Player ESP") || enabled.contains("Entity ESP"))
                ctx.drawTextWithShadow(mc.textRenderer, label, 10, 12 + row * 12, color);
            ScreenPoint point = projectEntity(mc, entity, sw, sh);
            if (point != null && enabled.contains("Nametags")) {
                String tagText = name;
                if (ClientConfig.nametagHealth && entity instanceof net.minecraft.entity.LivingEntity living) {
                    tagText += "  " + String.format(Locale.ROOT, "%.0f❤", living.getHealth());
                }
                int width = mc.textRenderer.getWidth(tagText);
                ctx.fill((int)point.x() - width / 2 - 3, (int)point.y() - 3,
                        (int)point.x() + width / 2 + 3, (int)point.y() + 10, 0x99000000);
                ctx.drawTextWithShadow(mc.textRenderer, tagText, (int)point.x() - width / 2, (int)point.y(), color);
            }
            if (point != null && enabled.contains("Tracers")) {
                int startY = "CENTER".equals(ClientConfig.tracerOrigin) ? sh / 2 : sh - 3;
                drawHudLine(ctx, centerX, startY, (int)point.x(), (int)point.y(), color);
            }
            row++;
        }
    }

    private static ScreenPoint projectEntity(MinecraftClient mc, Entity entity, int sw, int sh) {
        var camera = mc.gameRenderer.getCamera();
        var delta = entity.getPos().add(0, entity.getHeight() + 0.25, 0).subtract(camera.getPos());
        double yaw = Math.toRadians(camera.getYaw()), pitch = Math.toRadians(camera.getPitch());
        double cx = delta.x * Math.cos(yaw) + delta.z * Math.sin(yaw);
        double cz = -delta.x * Math.sin(yaw) + delta.z * Math.cos(yaw);
        double cy = delta.y * Math.cos(pitch) - cz * Math.sin(pitch);
        double depth = delta.y * Math.sin(pitch) + cz * Math.cos(pitch);
        if (depth <= 0.05) return null;
        double fov = mc.options.getFov().getValue();
        double focal = (sh * 0.5) / Math.tan(Math.toRadians(fov * 0.5));
        float sx = (float)(sw * 0.5 + cx * focal / depth), sy = (float)(sh * 0.5 - cy * focal / depth);
        if (sx < -100 || sx > sw + 100 || sy < -100 || sy > sh + 100) return null;
        return new ScreenPoint(sx, sy);
    }

    private static void drawHudLine(DrawContext ctx, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1-x0), sx = x0 < x1 ? 1 : -1, dy = -Math.abs(y1-y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            ctx.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    private static void renderStorageOverlay(DrawContext ctx, MinecraftClient mc) {
        if (!enabled.contains("Storage ESP") || cachedStorage.isEmpty()) return;
        int y = 150;
        for (StorageMarker marker : cachedStorage) {
            double distance = Math.sqrt(marker.pos().getSquaredDistance(mc.player.getBlockPos()));
            String text = marker.label() + "  " + String.format(Locale.ROOT, "%.1fm", distance);
            ctx.drawTextWithShadow(mc.textRenderer, text, 10, y,
                    marker.label().equals("Spawner") ? 0xFFFFB84D : 0xFF75D7C1);
            y += 12;
        }
    }

    private static void panel(DrawContext ctx, MinecraftClient mc, String label, int x, int y) {
        int width = mc.textRenderer.getWidth(label) + 14;
        ctx.fill(x, y, x + width, y + 19, panelColor());
        ctx.fill(x, y, x + 2, y + 19, accentWithAlpha());
        ctx.drawTextWithShadow(mc.textRenderer, label, x + 7, y + 6, 0xFFEAF2FF);
    }

    private static void key(DrawContext ctx, MinecraftClient mc, String label,
                            int x, int y, boolean pressed) {
        int width = label.equals("SPC") ? 40 : 20;
        ctx.fill(x, y, x + width, y + 20, pressed ? accentColor() : panelColor());
        ctx.drawCenteredTextWithShadow(mc.textRenderer, label, x + width / 2, y + 6, 0xFFFFFFFF);
    }

    private static boolean down(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setOption(MinecraftClient mc, String name, Object value) {
        try {
            Field f = findField(mc.options.getClass(), name);
            f.setAccessible(true);
            Object option = f.get(mc.options);
            Object old = option.getClass().getMethod("getValue").invoke(option);
            ClientConfig.originalOptions.putIfAbsent(name, old);
            Method setter = Arrays.stream(option.getClass().getMethods())
                    .filter(m -> m.getName().equals("setValue") && m.getParameterCount() == 1)
                    .findFirst().orElseThrow();
            setter.invoke(option, value);
        } catch (Exception ignored) { }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setEnumOption(MinecraftClient mc, String name, String enumName) {
        try {
            Field f = findField(mc.options.getClass(), name);
            f.setAccessible(true);
            Object option = f.get(mc.options);
            Object current = option.getClass().getMethod("getValue").invoke(option);
            ClientConfig.originalOptions.putIfAbsent(name, current);
            Object target = Enum.valueOf((Class<? extends Enum>) current.getClass(), enumName);
            Method setter = Arrays.stream(option.getClass().getMethods())
                    .filter(m -> m.getName().equals("setValue") && m.getParameterCount() == 1)
                    .findFirst().orElseThrow();
            setter.invoke(option, target);
        } catch (Exception ignored) { }
    }

    private static void restoreOption(MinecraftClient mc, String name) {
        Object value = ClientConfig.originalOptions.remove(name);
        if (value == null) return;
        try {
            Field f = findField(mc.options.getClass(), name);
            f.setAccessible(true);
            Object option = f.get(mc.options);
            Method setter = Arrays.stream(option.getClass().getMethods())
                    .filter(m -> m.getName().equals("setValue") && m.getParameterCount() == 1)
                    .findFirst().orElseThrow();
            setter.invoke(option, value);
        } catch (Exception ignored) { }
    }
}
