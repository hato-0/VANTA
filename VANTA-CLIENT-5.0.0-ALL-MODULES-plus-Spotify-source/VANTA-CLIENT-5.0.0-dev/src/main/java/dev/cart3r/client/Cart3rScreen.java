package dev.cart3r.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** VANTA module browser. Left click toggles; right click opens module settings. */
public class Cart3rScreen extends Screen {
    private static final int W = 510, H = 390;
    private int left, top, scroll;
    private String selectedModule = Cart3rClient.MODULES[0];
    private boolean waitingForBind;
    private boolean editHud;
    private String dragging;
    private int offsetX, offsetY;
    private final long openedAt = System.currentTimeMillis();
    private final List<Row> rows = new ArrayList<>();
    private record Row(String name, int x, int y, int width, int height) {}

    public Cart3rScreen() { super(Text.literal("VANTA CLIENT")); }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        left = (width - W) / 2;
        top = (height - H) / 2;
        float progress = Math.min(1f, (System.currentTimeMillis() - openedAt) / 180f);
        int slide = (int)((1f - progress) * 12);
        ctx.fill(left - 1, top - 1 + slide, left + W + 1, top + H + 1 + slide, Cart3rClient.accentColor());
        ctx.fill(left, top + slide, left + W, top + H + slide, 0xF210151D);
        ctx.fill(left, top + slide, left + W, top + 48 + slide, 0xFF1B2430);
        ctx.fill(left, top + 46 + slide, left + W, top + 48 + slide, Cart3rClient.accentColor());
        ctx.drawTextWithShadow(textRenderer, "VANTA", left + 14, top + 10 + slide, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, "MODULES  /  RIGHT CLICK FOR SETTINGS", left + 14, top + 27 + slide, 0xFF9BAFC8);
        ctx.drawTextWithShadow(textRenderer, "5.0", left + W - 34, top + 17 + slide, Cart3rClient.accentColor());

        int listX = left + 12, listY = top + 60 + slide, listW = 205;
        rows.clear();
        ctx.fill(listX, listY - 5, listX + listW, listY + 270, 0xAA111820);
        for (int i = 0; i < Cart3rClient.MODULES.length; i++) {
            String name = Cart3rClient.MODULES[i];
            int y = listY + i * 25 - scroll;
            if (y < listY - 22 || y > listY + 260) continue;
            boolean active = Cart3rClient.enabled.contains(name);
            boolean selected = name.equals(selectedModule);
            int bg = selected ? 0xFF2B3C50 : active ? 0xFF243343 : 0xFF1A2029;
            ctx.fill(listX + 3, y, listX + listW - 3, y + 21, bg);
            ctx.fill(listX + 3, y, listX + 5, y + 21, active ? Cart3rClient.accentColor() : 0xFF46515E);
            ctx.drawTextWithShadow(textRenderer, name, listX + 12, y + 7, 0xFFEAF2FF);
            ctx.drawTextWithShadow(textRenderer, active ? "ON" : "OFF", listX + listW - 34, y + 7,
                    active ? 0xFF73D6A1 : 0xFF9BAFC8);
            rows.add(new Row(name, listX + 3, y, listW - 6, 21));
        }

        int px = left + 230, py = top + 60 + slide, pw = W - 242;
        ctx.fill(px, py - 5, px + pw, py + 270, 0xAA111820);
        ctx.drawTextWithShadow(textRenderer, selectedModule, px + 12, py + 7, Cart3rClient.accentColor());
        ctx.drawTextWithShadow(textRenderer, "Module settings", px + 12, py + 24, 0xFF9BAFC8);
        ctx.fill(px + 10, py + 43, px + pw - 10, py + 44, 0xFF344252);
        ctx.drawTextWithShadow(textRenderer, "STATUS", px + 12, py + 57, 0xFF9BAFC8);
        ctx.drawTextWithShadow(textRenderer, Cart3rClient.enabled.contains(selectedModule) ? "ENABLED" : "DISABLED",
                px + 12, py + 73, Cart3rClient.enabled.contains(selectedModule) ? 0xFF73D6A1 : 0xFFFF7885);
        ctx.drawTextWithShadow(textRenderer, "KEYBIND", px + 12, py + 105, 0xFF9BAFC8);
        String bind = waitingForBind ? "PRESS ANY KEY..." : keyName(ClientConfig.keybinds.getOrDefault(selectedModule, -1));
        ctx.fill(px + 10, py + 121, px + pw - 10, py + 146, 0xFF253344);
        ctx.drawTextWithShadow(textRenderer, bind, px + 18, py + 130, waitingForBind ? 0xFFFFC66D : 0xFFEAF2FF);
        ctx.fill(px + 10, py + 154, px + pw - 10, py + 179, 0xFF253344);
        ctx.drawTextWithShadow(textRenderer, "RESET KEYBIND", px + 18, py + 163, 0xFFEAF2FF);
        renderModuleSettings(ctx, px, py);

        int fy = top + H - 54 + slide;
        ctx.fill(left + 12, fy, left + W - 12, fy + 1, 0xFF344252);
        ctx.drawTextWithShadow(textRenderer, "HUD EDIT: " + (editHud ? "ON" : "OFF"), left + 16, fy + 8,
                editHud ? 0xFF73D6A1 : 0xFF9BAFC8);
        ctx.drawTextWithShadow(textRenderer, "THEME: " + ClientConfig.theme, left + 155, fy + 8, Cart3rClient.accentColor());
        ctx.drawTextWithShadow(textRenderer, "OPACITY: " + ClientConfig.hudOpacity + "%", left + 300, fy + 8, 0xFF9BAFC8);
        ctx.drawTextWithShadow(textRenderer, "Right Shift / ESC to close  •  Scroll to browse", left + 16, fy + 27, 0xFF77889C);
        if (editHud) renderHudEdit(ctx);
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void renderModuleSettings(DrawContext ctx, int px, int py) {
        if (selectedModule.equals("Storage ESP")) {
            drawSetting(ctx, px, py + 194, "SCAN RANGE", ClientConfig.storageRange + " blocks");
        } else if (selectedModule.equals("Player ESP") || selectedModule.equals("Entity ESP")
                || selectedModule.equals("Tracers") || selectedModule.equals("Nametags")) {
            drawSetting(ctx, px, py + 194, "ESP RANGE", ClientConfig.espRange + " blocks");
            drawSetting(ctx, px, py + 220, "DISTANCE TEXT", ClientConfig.espDistance ? "ON" : "OFF");
            String extra = selectedModule.equals("Tracers") ? "ORIGIN: " + ClientConfig.tracerOrigin
                    : selectedModule.equals("Nametags") ? "HEALTH: " + (ClientConfig.nametagHealth ? "ON" : "OFF")
                    : "COLOR: " + ClientConfig.espColorMode;
            drawSetting(ctx, px, py + 246, "STYLE", extra);
        } else {
            ctx.drawTextWithShadow(textRenderer, "No custom settings yet", px + 12, py + 202, 0xFF9BAFC8);
        }
    }

    private void drawSetting(DrawContext ctx, int px, int y, String label, String value) {
        ctx.fill(px + 10, y, px + W - 252, y + 22, 0xFF253344);
        ctx.drawTextWithShadow(textRenderer, label, px + 16, y + 7, 0xFF9BAFC8);
        int valueWidth = textRenderer.getWidth(value);
        ctx.drawTextWithShadow(textRenderer, value, px + W - 264 - valueWidth, y + 7, Cart3rClient.accentColor());
    }

    private String keyName(int key) {
        if (key < 0) return "NONE  /  CLICK TO BIND";
        String name = GLFW.glfwGetKeyName(key, 0);
        if (name != null) return name.toUpperCase();
        return switch (key) {
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RIGHT SHIFT";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "LEFT SHIFT";
            case GLFW.GLFW_KEY_SPACE -> "SPACE";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "CAPS LOCK";
            default -> "KEY " + key;
        };
    }

    private void renderHudEdit(DrawContext ctx) {
        ClientConfig.ensureHudDefaults();
        for (var e : ClientConfig.HUD.entrySet()) {
            var p = e.getValue();
            ctx.fill(p.x - 2, p.y - 2, p.x + 116, p.y + 18, 0xCC172638);
            ctx.fill(p.x - 2, p.y - 2, p.x + 116, p.y - 1, Cart3rClient.accentColor());
            ctx.drawTextWithShadow(textRenderer, e.getKey(), p.x + 3, p.y + 4, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int listX = left + 12, listY = top + 60, pw = W - 242;
        int py = top + 60;
        if (button == 0 && editHud) {
            for (var e : ClientConfig.HUD.entrySet()) {
                var p = e.getValue();
                if (mx >= p.x - 2 && mx <= p.x + 116 && my >= p.y - 2 && my <= p.y + 18) {
                    dragging = e.getKey(); offsetX = (int)mx - p.x; offsetY = (int)my - p.y; return true;
                }
            }
        }
        for (Row r : rows) {
            if (mx >= r.x && mx <= r.x + r.width && my >= r.y && my <= r.y + r.height) {
                selectedModule = r.name;
                if (button == 0) {
                    if (!Cart3rClient.enabled.add(r.name)) Cart3rClient.enabled.remove(r.name);
                    ClientConfig.save();
                }
                return true;
            }
        }
        int px = left + 230;
        if (button == 0 && mx >= px + 10 && mx <= px + W - 252 && my >= py + 121 && my <= py + 146) {
            waitingForBind = true; return true;
        }
        if (button == 0 && mx >= px + 10 && mx <= px + W - 252 && my >= py + 154 && my <= py + 179) {
            ClientConfig.keybinds.remove(selectedModule); ClientConfig.save(); return true;
        }
        if (button == 0 && mx >= px + 10 && mx <= px + W - 252) {
            if (selectedModule.equals("Storage ESP") && my >= py + 194 && my <= py + 216) {
                ClientConfig.storageRange = ClientConfig.storageRange >= 64 ? 8 : ClientConfig.storageRange + 8;
                ClientConfig.save(); return true;
            }
            if (selectedModule.equals("Player ESP") || selectedModule.equals("Entity ESP")
                    || selectedModule.equals("Tracers") || selectedModule.equals("Nametags")) {
                if (my >= py + 194 && my <= py + 216) {
                    ClientConfig.espRange = ClientConfig.espRange >= 256 ? 16 : ClientConfig.espRange + 16;
                    ClientConfig.save(); return true;
                }
                if (my >= py + 220 && my <= py + 242) {
                    ClientConfig.espDistance = !ClientConfig.espDistance;
                    ClientConfig.save(); return true;
                }
                if (my >= py + 246 && my <= py + 268) {
                    if (selectedModule.equals("Tracers")) {
                        ClientConfig.tracerOrigin = "BOTTOM".equals(ClientConfig.tracerOrigin) ? "CENTER" : "BOTTOM";
                    } else if (selectedModule.equals("Nametags")) {
                        ClientConfig.nametagHealth = !ClientConfig.nametagHealth;
                    } else {
                        String[] colors = {"TEAM", "RED", "GREEN", "RAINBOW"};
                        int i = java.util.Arrays.asList(colors).indexOf(ClientConfig.espColorMode);
                        ClientConfig.espColorMode = colors[(i + 1) % colors.length];
                    }
                    ClientConfig.save(); return true;
                }
            }
        }
        int fy = top + H - 54;
        if (button == 0 && my >= fy + 3 && my <= fy + 22) {
            if (mx >= left + 12 && mx <= left + 145) { editHud = !editHud; dragging = null; return true; }
            if (mx >= left + 150 && mx <= left + 290) {
                String[] themes = {"LUNAR", "CRIMSON", "EMERALD", "VIOLET", "AMBER"};
                int i = java.util.Arrays.asList(themes).indexOf(ClientConfig.theme);
                ClientConfig.theme = themes[(i + 1) % themes.length]; ClientConfig.save(); return true;
            }
            if (mx >= left + 295 && mx <= left + W - 12) {
                ClientConfig.hudOpacity = ClientConfig.hudOpacity >= 100 ? 40 : Math.min(100, ClientConfig.hudOpacity + 15);
                ClientConfig.save(); return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (mx < left + 225 && my > top + 50 && my < top + H - 60) {
            int max = Math.max(0, Cart3rClient.MODULES.length * 25 - 270);
            scroll = Math.max(0, Math.min(max, scroll - (int)(vertical * 25)));
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging != null && button == 0) {
            var p = ClientConfig.HUD.get(dragging);
            if (p != null) { p.x = Math.max(0, Math.min(width - 120, (int)mx - offsetX)); p.y = Math.max(0, Math.min(height - 24, (int)my - offsetY)); }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override public boolean mouseReleased(double mx, double my, int button) {
        if (dragging != null) { dragging = null; ClientConfig.save(); return true; }
        return super.mouseReleased(mx, my, button);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (waitingForBind) {
            waitingForBind = false;
            if (keyCode != GLFW.GLFW_KEY_ESCAPE && keyCode != GLFW.GLFW_KEY_DELETE)
                ClientConfig.keybinds.put(selectedModule, keyCode);
            ClientConfig.save();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) { ClientConfig.save(); close(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public void close() { ClientConfig.save(); super.close(); }
    @Override public boolean shouldPause() { return false; }
}
