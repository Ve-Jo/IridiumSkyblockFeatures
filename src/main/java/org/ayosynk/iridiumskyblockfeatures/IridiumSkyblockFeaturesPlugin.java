package org.ayosynk.iridiumskyblockfeatures;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.data.HologramData;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class IridiumSkyblockFeaturesPlugin extends JavaPlugin implements Listener {

    private static final String NPC_TYPE_SPAWN = "spawn";
    private static final String NPC_TYPE_ISLAND = "island";

    private NamespacedKey npcTypeKey;
    private NamespacedKey islandKeyKey;

    private BukkitTask updateTask;

    private final Map<String, IslandRuntime> islands = new HashMap<>();
    private final Map<UUID, PlayerBorderRuntime> playerBorders = new HashMap<>();
    private UUID spawnVillagerUuid;

    private IridiumBridge iridium;
    private Boolean placeholderApiAvailable;
    private Method placeholderSetMethod;


    @Override
    public void onEnable() {
        saveDefaultConfig();

        npcTypeKey = new NamespacedKey(this, "npc_type");
        islandKeyKey = new NamespacedKey(this, "island_key");

        Bukkit.getPluginManager().registerEvents(this, this);

        Plugin iridiumPlugin = Bukkit.getPluginManager().getPlugin("IridiumSkyblock");
        if (iridiumPlugin == null || !iridiumPlugin.isEnabled()) {
            getLogger().severe("IridiumSkyblock не найден или не включен. Отключаю плагин.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        iridium = new IridiumBridge(iridiumPlugin);

        if (Bukkit.getPluginManager().getPlugin("FancyHolograms") == null) {
            getLogger().severe("FancyHolograms не найден. Отключаю плагин.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        findExistingNpcs();
        ensureSpawnVillager();

        long intervalTicks = Math.max(20L, 20L * getConfig().getLong("island-border-holograms.update-seconds", 30));
        updateTask = Bukkit.getScheduler().runTaskTimer(this, this::updateAllHolograms, intervalTicks, intervalTicks);

    }

    @Override
    public void onDisable() {
        if (updateTask != null) {
            updateTask.cancel();
        }
        for (PlayerBorderRuntime runtime : playerBorders.values()) {
            removeHolograms(runtime.hologramNames);
        }
        playerBorders.clear();

    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> ensureIslandNpcAndHolograms(player), 40L);
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> ensureIslandNpcAndHolograms(player), 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        PlayerBorderRuntime runtime = playerBorders.remove(event.getPlayer().getUniqueId());
        if (runtime != null) {
            removeHolograms(runtime.hologramNames);
        }
    }

    @EventHandler
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (!(entity instanceof Villager villager)) {
            return;
        }
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        String npcType = pdc.get(npcTypeKey, PersistentDataType.STRING);
        if (npcType == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            boolean ok = player.performCommand("is");
            if (!ok) {
                player.performCommand("island");
            }
        });
    }

    private void ensureIslandNpcAndHolograms(Player player) {
        if (!isSkyblockWorld(player.getWorld())) {
            removePlayerBorderHolograms(player);
            return;
        }

        Optional<IslandInfo> infoOpt = iridium.getIslandInfo(player);
        if (infoOpt.isEmpty()) {
            removePlayerBorderHolograms(player);
            return;
        }

        IslandInfo info = infoOpt.get();
        String islandKey = info.key();
        IslandRuntime runtime = islands.computeIfAbsent(islandKey, key -> new IslandRuntime());

        Location base = resolveIslandNpcLocation(player, info);
        if (base == null) {
            return;
        }

        double yOffset = getConfig().getDouble("island-villager.y-offset", 0.0);
        Location npcLocation = base.clone().add(0.0, yOffset, 0.0);
        npcLocation = npcLocation.toCenterLocation();
        npcLocation.setYaw(base.getYaw());
        npcLocation.setPitch(0f);

        Villager npc = runtime.villagerUuid == null ? null : getVillager(runtime.villagerUuid);
        if (npc == null || npc.isDead()) {
            List<Villager> existingNpcs = findNpcsByType(NPC_TYPE_ISLAND, islandKey);
            if (!existingNpcs.isEmpty()) {
                npc = selectNearestNpc(existingNpcs, npcLocation);
                removeDuplicateNpcs(existingNpcs, npc);
            } else {
                npc = spawnNpcVillager(npcLocation, NPC_TYPE_ISLAND, islandKey, getConfig().getString("island-villager.name", "Island Manager"));
            }
            runtime.villagerUuid = npc.getUniqueId();
        } else if (npc.getLocation().distanceSquared(npcLocation) > 0.25) {
            npc.teleport(npcLocation);
        }

        if (getConfig().getBoolean("island-villager.hologram.enabled", true)) {
            double holoOffset = getConfig().getDouble("island-villager.hologram.y-offset", 2.7);
            Location holoLoc = npcLocation.clone().add(0, holoOffset, 0);
            String holoName = "isfeatures_" + safeKey(islandKey) + "_npc";
            List<String> templates = normalizeCurrentIslandPlaceholders(getConfig().getStringList("text.villager-hologram-lines"));
            List<String> lines = formatLines(templates, info, player);
            upsertTextHologram(holoName, holoLoc, lines, org.bukkit.entity.Display.Billboard.VERTICAL);
            runtime.npcHologramName = holoName;
        }

        removePlayerBorderHolograms(player);
    }

    private Location resolveIslandNpcLocation(Player player, IslandInfo info) {
        Location home = info.homeLocation();
        if (home != null) {
            return home;
        }

        BorderInfo border = resolveIslandBorder(player);
        if (border != null) {
            Location center = border.center;
            World world = center.getWorld();
            if (world != null) {
                return center.clone().add(0.0, 0.0, 0.0);
            }
        }

        return player.getLocation();
    }

    private BorderInfo resolveIslandBorder(Player player) {
        BorderInfo islandBorder = null;
        if (iridium != null) {
            double fallbackSize = getConfig().getDouble("island-border-holograms.default-size", 200.0);
            islandBorder = iridium.getIslandBorder(player, fallbackSize);
        }

        WorldBorder wb = player.getWorldBorder();
        boolean wbValid = false;
        double wbSize = 0.0;
        Location wbCenter = null;
        if (wb != null) {
            wbSize = wb.getSize();
            wbCenter = wb.getCenter();
            wbValid = wbSize > 0 && wbSize < 10_000_000 && wbCenter != null && wbCenter.getWorld() != null;
        }

        if (islandBorder != null) {
            if (wbValid) {
                return new BorderInfo(wbCenter, islandBorder.size);
            }
            return islandBorder;
        }

        if (wbValid) {
            return new BorderInfo(wbCenter, wbSize);
        }
        return null;
    }

    private void upsertPlayerBorderHolograms(Player player, IslandInfo info, BorderInfo border) {
        UUID uuid = player.getUniqueId();
        PlayerBorderRuntime runtime = playerBorders.computeIfAbsent(uuid, key -> new PlayerBorderRuntime());

        List<String> lines = formatLines(getConfig().getStringList("text.border-hologram-lines"), info, player);
        double outsideOffset = getConfig().getDouble("island-border-holograms.outside-offset", 0.8);
        double half = border.size / 2.0;
        if (!Double.isFinite(outsideOffset)) {
            outsideOffset = 0.0;
        }
        if (Math.abs(outsideOffset) > half - 0.5) {
            outsideOffset = Math.copySign(Math.max(0.0, half - 0.5), outsideOffset);
        }

        Location center = border.center.clone();
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        double y = resolveBorderY(world, player);

        String legacyBase = "isfeatures_" + safeKey(info.key()) + "_border_";
        removeHolograms(List.of(legacyBase + "north", legacyBase + "south", legacyBase + "west", legacyBase + "east"));

        String baseName = "isfeatures_p_" + uuid + "_border_";
        runtime.hologramNames = List.of(baseName + "north", baseName + "south", baseName + "west", baseName + "east");

        double minX = center.getX() - half;
        double maxX = center.getX() + half;
        double minZ = center.getZ() - half;
        double maxZ = center.getZ() + half;
        double insideEps = 0.0001;

        double northZ = minZ + outsideOffset + insideEps;
        double southZ = maxZ - outsideOffset - insideEps;
        double westX = minX + outsideOffset + insideEps;
        double eastX = maxX - outsideOffset - insideEps;

        Location north = new Location(world, center.getX(), y, northZ, 0f, 0f);
        Location south = new Location(world, center.getX(), y, southZ, 180f, 0f);
        Location west = new Location(world, westX, y, center.getZ(), -90f, 0f);
        Location east = new Location(world, eastX, y, center.getZ(), 90f, 0f);

        upsertTextHologram(runtime.hologramNames.get(0), north, lines, org.bukkit.entity.Display.Billboard.FIXED);
        upsertTextHologram(runtime.hologramNames.get(1), south, lines, org.bukkit.entity.Display.Billboard.FIXED);
        upsertTextHologram(runtime.hologramNames.get(2), west, lines, org.bukkit.entity.Display.Billboard.FIXED);
        upsertTextHologram(runtime.hologramNames.get(3), east, lines, org.bukkit.entity.Display.Billboard.FIXED);
    }

    private void removePlayerBorderHolograms(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerBorderRuntime runtime = playerBorders.remove(uuid);
        if (runtime == null) {
            return;
        }
        removeHolograms(runtime.hologramNames);
    }

    private void removeHolograms(List<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        HologramManager manager = FancyHologramsPlugin.get().getHologramManager();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            manager.getHologram(name).ifPresent(manager::removeHologram);
        }
    }

    private double resolveBorderY(World world, Player player) {
        boolean playerRelative = getConfig().getBoolean("island-border-holograms.player-relative-y", true);
        double y;
        if (playerRelative) {
            double offset = getConfig().getDouble("island-border-holograms.player-y-offset", 1.8);
            y = player.getLocation().getY() + offset;
        } else {
            y = getConfig().getDouble("island-border-holograms.y", player.getLocation().getY());
        }

        int min = world.getMinHeight();
        int max = world.getMaxHeight();
        double clamped = Math.max(min + 2.0, Math.min(max - 2.0, y));
        return clamped;
    }

    private boolean isSkyblockWorld(World world) {
        if (world == null) {
            return false;
        }
        if (iridium != null && iridium.isIslandWorld(world)) {
            return true;
        }
        List<String> configured = getConfig().getStringList("skyblock-worlds");
        if (configured != null && !configured.isEmpty()) {
            for (String name : configured) {
                if (name != null && name.equalsIgnoreCase(world.getName())) {
                    return true;
                }
            }
            return false;
        }
        return world.getName().startsWith("IridiumSkyblock");
    }

    private void updateAllHolograms() {
        if (islands.isEmpty()) {
            return;
        }

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        for (Player player : players) {
            ensureIslandNpcAndHolograms(player);
        }
    }

    private void ensureSpawnVillager() {
        if (!getConfig().getBoolean("spawn-villager.enabled", true)) {
            return;
        }

        Location loc = readLocation("spawn-villager");
        if (loc == null || loc.getWorld() == null) {
            return;
        }

        Villager existing = spawnVillagerUuid == null ? null : getVillager(spawnVillagerUuid);
        if (existing == null) {
            List<Villager> existingNpcs = findNpcsByType(NPC_TYPE_SPAWN, null);
            if (!existingNpcs.isEmpty()) {
                existing = selectNearestNpc(existingNpcs, loc);
                removeDuplicateNpcs(existingNpcs, existing);
            }
        }

        String name = getConfig().getString("spawn-villager.name", "Island Manager");

        if (existing == null) {
            Villager spawned = spawnNpcVillager(loc, NPC_TYPE_SPAWN, null, name);
            spawnVillagerUuid = spawned.getUniqueId();
        } else {
            spawnVillagerUuid = existing.getUniqueId();
            if (existing.getLocation().distanceSquared(loc) > 0.25) {
                existing.teleport(loc);
            }
            if (name != null && !name.isBlank()) {
                existing.setCustomName(name);
                existing.setCustomNameVisible(true);
            }
        }

        if (getConfig().getBoolean("spawn-villager.hologram.enabled", true)) {
            Villager villager = getVillager(spawnVillagerUuid);
            if (villager != null) {
                double holoOffset = getConfig().getDouble("spawn-villager.hologram.y-offset", 2.7);
                Location holoLoc = villager.getLocation().clone().add(0, holoOffset, 0);
                List<String> lines = getConfig().getStringList("text.villager-hologram-lines");
                IslandInfo info = new IslandInfo("spawn", "Spawn", 0, 0, null);
                upsertTextHologram("isfeatures_spawn_npc", holoLoc, formatLines(lines, info, null), org.bukkit.entity.Display.Billboard.VERTICAL);
            }
        }
    }

    private Location readLocation(String basePath) {
        String worldName = getConfig().getString(basePath + ".world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) {
            World fallback = Bukkit.getWorld("worlds");
            if (fallback != null) {
                world = fallback;
            } else if (Bukkit.getWorld("world") != null) {
                world = Bukkit.getWorld("world");
            } else if (!Bukkit.getWorlds().isEmpty()) {
                world = Bukkit.getWorlds().get(0);
            }
        }
        if (world == null) {
            return null;
        }
        double x = getConfig().getDouble(basePath + ".x", world.getSpawnLocation().getX());
        double y = getConfig().getDouble(basePath + ".y", world.getSpawnLocation().getY());
        double z = getConfig().getDouble(basePath + ".z", world.getSpawnLocation().getZ());
        float yaw = (float) getConfig().getDouble(basePath + ".yaw", 0.0);
        float pitch = (float) getConfig().getDouble(basePath + ".pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    private void findExistingNpcs() {
        Villager spawn = findNpcByType(NPC_TYPE_SPAWN, null);
        if (spawn != null) {
            spawnVillagerUuid = spawn.getUniqueId();
        }

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Villager villager)) {
                    continue;
                }
                PersistentDataContainer pdc = villager.getPersistentDataContainer();
                String type = pdc.get(npcTypeKey, PersistentDataType.STRING);
                if (!NPC_TYPE_ISLAND.equals(type)) {
                    continue;
                }
                String islandKey = pdc.get(islandKeyKey, PersistentDataType.STRING);
                if (islandKey == null || islandKey.isBlank()) {
                    continue;
                }
                IslandRuntime runtime = islands.computeIfAbsent(islandKey, key -> new IslandRuntime());
                runtime.villagerUuid = villager.getUniqueId();
            }
        }
    }

    private Villager findNpcByType(String type, String islandKey) {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Villager villager)) {
                    continue;
                }
                PersistentDataContainer pdc = villager.getPersistentDataContainer();
                String foundType = pdc.get(npcTypeKey, PersistentDataType.STRING);
                if (!Objects.equals(foundType, type)) {
                    continue;
                }
                if (islandKey != null) {
                    String foundIsland = pdc.get(islandKeyKey, PersistentDataType.STRING);
                    if (!Objects.equals(foundIsland, islandKey)) {
                        continue;
                    }
                }
                return villager;
            }
        }
        return null;
    }

    private List<Villager> findNpcsByType(String type, String islandKey) {
        List<Villager> out = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Villager villager)) {
                    continue;
                }
                PersistentDataContainer pdc = villager.getPersistentDataContainer();
                String foundType = pdc.get(npcTypeKey, PersistentDataType.STRING);
                if (!Objects.equals(foundType, type)) {
                    continue;
                }
                if (islandKey != null) {
                    String foundIsland = pdc.get(islandKeyKey, PersistentDataType.STRING);
                    if (!Objects.equals(foundIsland, islandKey)) {
                        continue;
                    }
                }
                out.add(villager);
            }
        }
        return out;
    }

    private Villager selectNearestNpc(List<Villager> npcs, Location target) {
        Villager best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Villager villager : npcs) {
            double dist = villager.getLocation().distanceSquared(target);
            if (dist < bestDistance) {
                bestDistance = dist;
                best = villager;
            }
        }
        return best;
    }

    private void removeDuplicateNpcs(List<Villager> npcs, Villager keep) {
        if (npcs == null || npcs.isEmpty()) {
            return;
        }
        for (Villager villager : npcs) {
            if (villager == null || villager.equals(keep)) {
                continue;
            }
            villager.remove();
        }
    }

    private Villager spawnNpcVillager(Location location, String npcType, String islandKey, String name) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalStateException("World is null");
        }

        Villager villager = (Villager) world.spawnEntity(location, EntityType.VILLAGER);
        villager.setAI(false);
        villager.setInvulnerable(true);
        villager.setSilent(true);
        villager.setCollidable(false);
        villager.setRemoveWhenFarAway(false);
        villager.setPersistent(true);

        if (name != null && !name.isBlank()) {
            villager.setCustomName(name);
            villager.setCustomNameVisible(true);
        } else {
            villager.setCustomNameVisible(false);
        }

        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        pdc.set(npcTypeKey, PersistentDataType.STRING, npcType);
        if (islandKey != null) {
            pdc.set(islandKeyKey, PersistentDataType.STRING, islandKey);
        }

        return villager;
    }

    private Villager getVillager(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(uuid);
        if (!(entity instanceof Villager villager)) {
            return null;
        }
        return villager;
    }

    private void upsertTextHologram(String name, Location location, List<String> lines, org.bukkit.entity.Display.Billboard billboard) {
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
        data.setBillboard(billboard == null ? org.bukkit.entity.Display.Billboard.FIXED : billboard);
        data.setTextAlignment(org.bukkit.entity.TextDisplay.TextAlignment.CENTER);
        data.setScale(new org.joml.Vector3f(1.5f, 1.5f, 1.5f));
        data.setText(lines);
        data.setSeeThrough(false);
        data.setPersistent(false);

        Hologram hologram = manager.create(data);
        manager.addHologram(hologram);
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

    private List<String> formatLines(List<String> templates, IslandInfo info, Player player) {
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

    private List<String> normalizeCurrentIslandPlaceholders(List<String> templates) {
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
        Plugin plugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (plugin == null || !plugin.isEnabled()) {
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

    private String safeKey(String key) {
        String s = key == null ? "unknown" : key;
        s = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]+", "_");
        if (s.length() > 64) {
            s = s.substring(0, 64);
        }
        return s;
    }

    private record BorderInfo(Location center, double size) {}

    private record IslandInfo(String key, String name, int level, double value, Location homeLocation) {}

    private static final class IslandRuntime {
        private UUID villagerUuid;
        private String npcHologramName;
    }

    private static final class PlayerBorderRuntime {
        private List<String> hologramNames;
    }

    private static final class IridiumBridge {

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

        private IridiumBridge(Plugin plugin) {
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

        public Optional<IslandInfo> getIslandInfo(Player player) {
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

        public BorderInfo getIslandBorder(Player player, double fallbackSize) {
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

        public boolean isIslandWorld(World world) {
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
}
