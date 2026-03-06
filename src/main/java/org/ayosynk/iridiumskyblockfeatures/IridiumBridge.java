package org.ayosynk.iridiumskyblockfeatures;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class IridiumBridge {

    private final Plugin plugin;
    private final Class<?> apiClass;
    private final Method apiGetInstance;
    private final Method apiGetIslandViaLocation;
    private final Method apiIsIslandWorld;
    private final Map<Integer, Double> sizeLevelToSize = new HashMap<>();

    private Method islandGetId;
    private Method islandGetName;
    private Method islandGetLevel;
    private Method islandGetValue;
    private Method islandGetHome;
    private Method islandGetCenter;
    private Method islandGetBorderSize;
    private Method islandGetSize;
    private Method islandGetSizeLevel;
    private Method islandGetRadius;

    IridiumBridge(Plugin plugin) {
        this.plugin = plugin;

        try {
            apiClass = Class.forName("com.iridium.iridiumskyblock.api.IridiumSkyblockAPI");
            apiGetInstance = apiClass.getMethod("getInstance");
            apiGetInstance.setAccessible(true);
            apiGetIslandViaLocation = apiClass.getMethod("getIslandViaLocation", Location.class);
            apiGetIslandViaLocation.setAccessible(true);
            Method isIslandWorld = null;
            try {
                isIslandWorld = apiClass.getMethod("isIslandWorld", World.class);
                isIslandWorld.setAccessible(true);
            } catch (NoSuchMethodException ignored) {
            }
            apiIsIslandWorld = isIslandWorld;
            loadSizeLevels();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize IridiumSkyblock bridge", e);
        }
    }

    Optional<IslandInfo> getIslandInfo(Player player) {
        Object island = getIslandObject(player);
        if (island == null) {
            return Optional.empty();
        }

        initIslandMethods(island.getClass());

        String key = asString(invoke(island, islandGetId));
        String name = asString(invoke(island, islandGetName));
        Integer level = asInt(invoke(island, islandGetLevel));
        Double value = asDouble(invoke(island, islandGetValue));
        Location home = asLocation(invoke(island, islandGetHome));

        if (key == null || key.isBlank()) {
            key = name != null && !name.isBlank() ? name : player.getUniqueId().toString();
        }
        if (name == null || name.isBlank()) {
            name = "Island";
        }
        return Optional.of(new IslandInfo(key, name, level == null ? 0 : level, value == null ? 0.0 : value, home));
    }

    BorderInfo getIslandBorder(Player player, double fallbackSize) {
        Object island = getIslandObject(player);
        if (island == null) {
            return null;
        }

        initIslandMethods(island.getClass());

        Location center = asLocation(invoke(island, islandGetCenter));
        if (center == null) {
            center = asLocation(invoke(island, islandGetHome));
        }
        if (center == null) {
            center = player.getLocation();
        }
        if (center.getWorld() == null) {
            center = new Location(player.getWorld(), center.getX(), center.getY(), center.getZ(), center.getYaw(), center.getPitch());
        }

        Double size = resolveIslandSize(island, fallbackSize);
        if (size == null || !Double.isFinite(size) || size <= 0 || size >= 10_000_000) {
            return null;
        }

        return new BorderInfo(center, size);
    }

    boolean isIslandWorld(World world) {
        if (world == null) {
            return false;
        }
        if (apiIsIslandWorld == null) {
            return false;
        }
        try {
            Object api = apiGetInstance.invoke(null);
            if (api == null) {
                return false;
            }
            Object target = java.lang.reflect.Modifier.isStatic(apiIsIslandWorld.getModifiers()) ? null : api;
            Object result = apiIsIslandWorld.invoke(target, world);
            return result instanceof Boolean b && b;
        } catch (Exception e) {
            return false;
        }
    }

    private Object getIslandObject(Player player) {
        try {
            Object api = apiGetInstance.invoke(null);
            if (api == null) {
                return null;
            }
            Object target = java.lang.reflect.Modifier.isStatic(apiGetIslandViaLocation.getModifiers()) ? null : api;
            Object result = apiGetIslandViaLocation.invoke(target, player.getLocation());
            if (result == null) {
                return null;
            }
            if (result instanceof Optional<?> opt) {
                return opt.orElse(null);
            }
            if (result instanceof java.util.concurrent.CompletableFuture<?> future) {
                return future.join();
            }
            return result;
        } catch (Exception e) {
            plugin.getLogger().warning("IridiumSkyblock bridge error: " + e.getMessage());
            return null;
        }
    }

    private void initIslandMethods(Class<?> islandClass) {
        if (islandGetName != null) {
            return;
        }
        islandGetId = findAnyMethod(islandClass, List.of("getId", "getUUID", "getIslandId", "getIdentifier"));
        islandGetName = findAnyMethod(islandClass, List.of("getName", "getIslandName", "getDisplayName", "getIslandName"));
        islandGetLevel = findAnyMethod(islandClass, List.of("getLevel", "getIslandLevel"));
        islandGetValue = findAnyMethod(islandClass, List.of("getValue", "getIslandValue", "getWorth"));
        islandGetHome = findAnyMethod(islandClass, List.of("getHome", "getHomeLocation", "getSpawn", "getSpawnLocation"));
        islandGetCenter = findAnyMethod(islandClass, List.of("getCenter", "getCenterLocation", "getIslandCenter", "getOrigin", "getMiddle", "getLocation"));
        islandGetBorderSize = findAnyMethod(islandClass, List.of("getBorderSize", "getIslandSize", "getSize"));
        islandGetSize = findAnyMethod(islandClass, List.of("getSize", "getIslandSize", "getBorderSize"));
        islandGetSizeLevel = findAnyMethod(islandClass, List.of("getSizeLevel", "getIslandSizeLevel", "getBorderLevel", "getIslandSizeUpgradeLevel", "getSizeUpgradeLevel"));
        islandGetRadius = findAnyMethod(islandClass, List.of("getRadius"));
    }

    private void loadSizeLevels() {
        try {
            File enhancements = new File(plugin.getDataFolder(), "enhancements.yml");
            if (!enhancements.exists()) {
                return;
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(enhancements);
            ConfigurationSection levels = config.getConfigurationSection("sizeEnhancement.levels");
            if (levels == null) {
                return;
            }
            for (String key : levels.getKeys(false)) {
                try {
                    int level = Integer.parseInt(key);
                    double size = levels.getDouble(key + ".size", Double.NaN);
                    if (Double.isFinite(size) && size > 0) {
                        sizeLevelToSize.put(level, size);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private Double resolveIslandSize(Object island, double fallbackSize) {
        Double size = asDouble(invoke(island, islandGetBorderSize));
        if (size == null) {
            size = asDouble(invoke(island, islandGetSize));
        }
        if (size == null) {
            size = asDouble(invoke(island, islandGetRadius));
            if (size != null) {
                size = size * 2.0;
            }
        }

        Integer sizeLevel = asInt(invoke(island, islandGetSizeLevel));
        if (sizeLevel != null) {
            Double mapped = sizeLevelToSize.get(sizeLevel);
            if (mapped != null) {
                size = mapped;
            }
        }

        if (size != null && size <= 20) {
            Double mapped = sizeLevelToSize.get(size.intValue());
            if (mapped != null) {
                size = mapped;
            }
        }

        if (size == null) {
            size = fallbackSize;
        }
        return size;
    }

    private static Method findAnyMethod(Class<?> clazz, List<String> names) {
        for (String name : names) {
            try {
                Method m = clazz.getMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Object invoke(Object target, Method method) {
        if (target == null || method == null) {
            return null;
        }
        try {
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        return String.valueOf(value);
    }

    private static Integer asInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private static Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private static Location asLocation(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Location loc) {
            return loc;
        }
        return null;
    }
}
