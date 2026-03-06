package org.ayosynk.iridiumskyblockfeatures;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.data.HologramData;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class IslandTextSupport {

    private final JavaPlugin plugin;
    private Boolean placeholderApiAvailable;
    private Method placeholderSetMethod;

    IslandTextSupport(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void upsertTextHologram(String name, Location location, List<String> lines, Display.Billboard billboard) {
        HologramManager manager = FancyHologramsPlugin.get().getHologramManager();
        Hologram existing = manager.getHologram(name).orElse(null);
        if (existing != null) {
            HologramData data = existing.getData();
            if (data instanceof TextHologramData textData) {
                trySetHologramLocation(textData, location);
                if (billboard != null) {
                    textData.setBillboard(billboard);
                }
                textData.setText(lines);
                existing.queueUpdate();
                return;
            }
            manager.removeHologram(existing);
        }

        TextHologramData data = new TextHologramData(name, location);
        data.setBillboard(billboard == null ? Display.Billboard.FIXED : billboard);
        data.setTextAlignment(TextDisplay.TextAlignment.CENTER);
        data.setScale(new Vector3f(1.5f, 1.5f, 1.5f));
        data.setText(lines);
        data.setSeeThrough(false);
        data.setPersistent(false);

        Hologram hologram = manager.create(data);
        manager.addHologram(hologram);
    }

    List<String> formatLines(List<String> templates, IslandInfo info, Player player) {
        List<String> out = new ArrayList<>(templates.size());
        for (String template : templates) {
            if (template == null) {
                continue;
            }
            String line = template
                    .replace("{name}", info.name() == null ? "" : info.name())
                    .replace("{level}", String.valueOf(info.level()))
                    .replace("{value}", formatNumber(info.value()));
            out.add(line);
        }
        return applyPlaceholders(player, out);
    }

    List<String> normalizeCurrentIslandPlaceholders(List<String> templates) {
        if (templates == null || templates.isEmpty()) {
            return templates;
        }
        List<String> out = new ArrayList<>(templates.size());
        for (String template : templates) {
            if (template == null) {
                continue;
            }
            out.add(template.replace("%iridiumskyblock_island_", "%iridiumskyblock_current_island_"));
        }
        return out;
    }

    String safeKey(String key) {
        String s = key == null ? "unknown" : key;
        s = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]+", "_");
        if (s.length() > 64) {
            s = s.substring(0, 64);
        }
        return s;
    }

    private void trySetHologramLocation(Object hologramData, Location location) {
        if (hologramData == null || location == null) {
            return;
        }
        for (String methodName : List.of("setLocation", "setPosition")) {
            try {
                Method method = hologramData.getClass().getMethod(methodName, Location.class);
                method.setAccessible(true);
                method.invoke(hologramData, location);
                return;
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignored) {
                return;
            }
        }
    }

    private List<String> applyPlaceholders(Player player, List<String> lines) {
        if (player == null || lines == null || lines.isEmpty()) {
            return lines;
        }
        if (!ensurePlaceholderApi()) {
            return lines;
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String resolved = line;
            try {
                Object value = placeholderSetMethod.invoke(null, player, line);
                if (value != null) {
                    resolved = String.valueOf(value);
                }
            } catch (Exception ignored) {
            }
            out.add(resolved);
        }
        return out;
    }

    private boolean ensurePlaceholderApi() {
        if (placeholderApiAvailable != null) {
            return placeholderApiAvailable;
        }
        Plugin placeholderPlugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (placeholderPlugin == null || !placeholderPlugin.isEnabled()) {
            placeholderApiAvailable = false;
            return false;
        }
        try {
            Class<?> clazz = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            placeholderSetMethod = clazz.getMethod("setPlaceholders", Player.class, String.class);
            placeholderSetMethod.setAccessible(true);
            placeholderApiAvailable = true;
            return true;
        } catch (Exception e) {
            placeholderApiAvailable = false;
            return false;
        }
    }

    private String formatNumber(double number) {
        if (!Double.isFinite(number)) {
            return "0";
        }
        double abs = Math.abs(number);
        if (abs < 1000) {
            return stripTrailingZeros(number);
        }
        if (abs < 1_000_000) {
            return stripTrailingZeros(number / 1000.0) + "k";
        }
        if (abs < 1_000_000_000) {
            return stripTrailingZeros(number / 1_000_000.0) + "m";
        }
        return stripTrailingZeros(number / 1_000_000_000.0) + "b";
    }

    private String stripTrailingZeros(double number) {
        BigDecimal bd = BigDecimal.valueOf(number).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return bd.toPlainString();
    }
}
