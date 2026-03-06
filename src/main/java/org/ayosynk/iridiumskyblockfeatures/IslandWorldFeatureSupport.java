package org.ayosynk.iridiumskyblockfeatures;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class IslandWorldFeatureSupport implements Listener {

    private static final String NPC_TYPE_SPAWN = "spawn";
    private static final String NPC_TYPE_ISLAND = "island";
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    private final JavaPlugin plugin;
    private final NamespacedKey npcTypeKey;
    private final NamespacedKey islandKeyKey;
    private final IridiumBridge iridium;
    private final IslandTextSupport textSupport;
    private final Map<String, IslandRuntime> islands = new HashMap<>();
    private final Map<UUID, PlayerBorderRuntime> playerBorders = new HashMap<>();

    private UUID spawnVillagerUuid;
    private BukkitTask updateTask;

    IslandWorldFeatureSupport(JavaPlugin plugin, NamespacedKey npcTypeKey, NamespacedKey islandKeyKey, IridiumBridge iridium) {
        this.plugin = plugin;
        this.npcTypeKey = npcTypeKey;
        this.islandKeyKey = islandKeyKey;
        this.iridium = iridium;
        this.textSupport = new IslandTextSupport(plugin);
    }

    void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        findExistingNpcs();
        ensureSpawnVillager();
        long intervalTicks = Math.max(20L, 20L * plugin.getConfig().getLong("island-border-holograms.update-seconds", 30));
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAllHolograms, intervalTicks, intervalTicks);
    }

    void disable() {
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
        Bukkit.getScheduler().runTaskLater(plugin, () -> ensureIslandNpcAndHolograms(player), 40L);
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> ensureIslandNpcAndHolograms(player), 20L);
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
        Bukkit.getScheduler().runTask(plugin, () -> {
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
            removePlayerBorderHolograms(player);
            return;
        }

        double yOffset = plugin.getConfig().getDouble("island-villager.y-offset", 0.0);
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
                npc = spawnNpcVillager(npcLocation, NPC_TYPE_ISLAND, islandKey, plugin.getConfig().getString("island-villager.name", "Island Manager"));
            }
            runtime.villagerUuid = npc.getUniqueId();
        } else if (npc.getLocation().distanceSquared(npcLocation) > 0.25) {
            npc.teleport(npcLocation);
        }

        if (plugin.getConfig().getBoolean("island-villager.hologram.enabled", true)) {
            double holoOffset = plugin.getConfig().getDouble("island-villager.hologram.y-offset", 2.7);
            Location holoLoc = npcLocation.clone().add(0, holoOffset, 0);
            String holoName = "isfeatures_" + textSupport.safeKey(islandKey) + "_npc";
            List<String> templates = textSupport.normalizeCurrentIslandPlaceholders(plugin.getConfig().getStringList("text.villager-hologram-lines"));
            List<String> lines = textSupport.formatLines(templates, info, player);
            textSupport.upsertTextHologram(holoName, holoLoc, lines, org.bukkit.entity.Display.Billboard.VERTICAL);
        }

        if (plugin.getConfig().getBoolean("island-border-holograms.enabled", false)) {
            BorderInfo border = resolveIslandBorder(player);
            if (border != null) {
                upsertPlayerBorderHolograms(player, info, border);
            } else {
                removePlayerBorderHolograms(player);
            }
        } else {
            removePlayerBorderHolograms(player);
        }
    }

    private Location resolveIslandNpcLocation(Player player, IslandInfo info) {
        Location home = info.homeLocation();
        if (home != null) {
            return home;
        }

        BorderInfo border = resolveIslandBorder(player);
        if (border != null) {
            Location center = border.center();
            World world = center.getWorld();
            if (world != null) {
                return center.clone();
            }
        }

        return player.getLocation();
    }

    private BorderInfo resolveIslandBorder(Player player) {
        BorderInfo islandBorder = null;
        double fallbackSize = plugin.getConfig().getDouble("island-border-holograms.default-size", 200.0);
        islandBorder = iridium.getIslandBorder(player, fallbackSize);

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
                return new BorderInfo(wbCenter, islandBorder.size());
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

        List<String> lines = textSupport.formatLines(plugin.getConfig().getStringList("text.border-hologram-lines"), info, player);
        double outsideOffset = plugin.getConfig().getDouble("island-border-holograms.outside-offset", 0.8);
        double half = border.size() / 2.0;
        if (!Double.isFinite(outsideOffset)) {
            outsideOffset = 0.0;
        }
        if (Math.abs(outsideOffset) > half - 0.5) {
            outsideOffset = Math.copySign(Math.max(0.0, half - 0.5), outsideOffset);
        }

        Location center = border.center().clone();
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        double y = resolveBorderY(world, player);

        String legacyBase = "isfeatures_" + textSupport.safeKey(info.key()) + "_border_";
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

        textSupport.upsertTextHologram(runtime.hologramNames.get(0), north, lines, org.bukkit.entity.Display.Billboard.FIXED);
        textSupport.upsertTextHologram(runtime.hologramNames.get(1), south, lines, org.bukkit.entity.Display.Billboard.FIXED);
        textSupport.upsertTextHologram(runtime.hologramNames.get(2), west, lines, org.bukkit.entity.Display.Billboard.FIXED);
        textSupport.upsertTextHologram(runtime.hologramNames.get(3), east, lines, org.bukkit.entity.Display.Billboard.FIXED);
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
        de.oliver.fancyholograms.api.HologramManager manager = de.oliver.fancyholograms.api.FancyHologramsPlugin.get().getHologramManager();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            manager.getHologram(name).ifPresent(manager::removeHologram);
        }
    }

    private double resolveBorderY(World world, Player player) {
        boolean playerRelative = plugin.getConfig().getBoolean("island-border-holograms.player-relative-y", true);
        double y;
        if (playerRelative) {
            double offset = plugin.getConfig().getDouble("island-border-holograms.player-y-offset", 1.8);
            y = player.getLocation().getY() + offset;
        } else {
            y = plugin.getConfig().getDouble("island-border-holograms.y", player.getLocation().getY());
        }

        int min = world.getMinHeight();
        int max = world.getMaxHeight();
        return Math.max(min + 2.0, Math.min(max - 2.0, y));
    }

    private boolean isSkyblockWorld(World world) {
        if (world == null) {
            return false;
        }
        if (iridium.isIslandWorld(world)) {
            return true;
        }
        List<String> configured = plugin.getConfig().getStringList("skyblock-worlds");
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
        if (!plugin.getConfig().getBoolean("spawn-villager.enabled", true)) {
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

        String name = plugin.getConfig().getString("spawn-villager.name", "Island Manager");

        if (existing == null) {
            Villager spawned = spawnNpcVillager(loc, NPC_TYPE_SPAWN, null, name);
            spawnVillagerUuid = spawned.getUniqueId();
        } else {
            spawnVillagerUuid = existing.getUniqueId();
            if (existing.getLocation().distanceSquared(loc) > 0.25) {
                existing.teleport(loc);
            }
            if (name != null && !name.isBlank()) {
                existing.customName(LEGACY_SERIALIZER.deserialize(name));
                existing.setCustomNameVisible(true);
            } else {
                existing.customName(null);
                existing.setCustomNameVisible(false);
            }
        }

        if (plugin.getConfig().getBoolean("spawn-villager.hologram.enabled", true)) {
            Villager villager = getVillager(spawnVillagerUuid);
            if (villager != null) {
                double holoOffset = plugin.getConfig().getDouble("spawn-villager.hologram.y-offset", 2.7);
                Location holoLoc = villager.getLocation().clone().add(0, holoOffset, 0);
                List<String> lines = plugin.getConfig().getStringList("text.villager-hologram-lines");
                IslandInfo info = new IslandInfo("spawn", "Spawn", 0, 0, null);
                textSupport.upsertTextHologram("isfeatures_spawn_npc", holoLoc, textSupport.formatLines(lines, info, null), org.bukkit.entity.Display.Billboard.VERTICAL);
            }
        }
    }

    private Location readLocation(String basePath) {
        String worldName = plugin.getConfig().getString(basePath + ".world");
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
        double x = plugin.getConfig().getDouble(basePath + ".x", world.getSpawnLocation().getX());
        double y = plugin.getConfig().getDouble(basePath + ".y", world.getSpawnLocation().getY());
        double z = plugin.getConfig().getDouble(basePath + ".z", world.getSpawnLocation().getZ());
        float yaw = (float) plugin.getConfig().getDouble(basePath + ".yaw", 0.0);
        float pitch = (float) plugin.getConfig().getDouble(basePath + ".pitch", 0.0);
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
            villager.customName(LEGACY_SERIALIZER.deserialize(name));
            villager.setCustomNameVisible(true);
        } else {
            villager.customName(null);
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
}
