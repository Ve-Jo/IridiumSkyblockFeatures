package org.ayosynk.iridiumskyblockfeatures;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;

final class MergedHooksSupport implements Listener {

    private static final String MOB_GRIEF_ENHANCEMENT_NAME = "mobGriefEnhancement";

    private final JavaPlugin plugin;
    private final NamespacedKey npcTypeKey;
    private final MergedHooksIridiumAccess iridium;

    private Method slimefunCheckMethod;
    private Method slimefunHasBlockInfoMethod;

    MergedHooksSupport(JavaPlugin plugin, NamespacedKey npcTypeKey) {
        this.plugin = plugin;
        this.npcTypeKey = npcTypeKey;
        this.iridium = new MergedHooksIridiumAccess(plugin);
    }

    void enable() {
        registerMobGriefUpgrade();
        setupSlimefunCheck();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerSlimefunEvents();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        if (!isHandledExploder(entity)) {
            return;
        }

        Location location = event.getLocation();
        Optional<Object> island = iridium.getIslandViaLocation(location);
        if (island.isEmpty()) {
            return;
        }

        if (entity instanceof Creeper creeper && creeper.getTarget() instanceof Player player
                && !iridium.hasPermission(island.get(), player, "BLOCK_BREAK")) {
            event.setCancelled(true);
            return;
        }

        event.blockList().removeIf(block -> isMobGriefPreventionEnabled(block.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Enderman)) {
            return;
        }
        if (isMobGriefPreventionEnabled(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        Block slimefunBlock = resolveSlimefunBlock(clicked);
        if (slimefunBlock == null) {
            return;
        }

        Optional<Object> island = iridium.getIslandViaLocation(slimefunBlock.getLocation());
        if (island.isEmpty()) {
            return;
        }

        if (!iridium.hasPermission(island.get(), event.getPlayer(), "OPEN_CONTAINERS")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (!(entity instanceof Villager) && !(entity instanceof WanderingTrader)) {
            return;
        }
        if (entity.getPersistentDataContainer().has(npcTypeKey, PersistentDataType.STRING)) {
            return;
        }
        if (iridium.getIslandViaLocation(entity.getLocation()).isEmpty()) {
            return;
        }
        event.setCancelled(false);
    }

    private void registerSlimefunEvents() {
        if (Bukkit.getPluginManager().getPlugin("Slimefun") == null) {
            return;
        }
        registerDynamicEvent("io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent", this::handleSlimefunRightClick);
        registerDynamicEvent("io.github.thebusybiscuit.slimefun4.api.events.MultiBlockInteractEvent", this::handleSlimefunMultiBlockInteract);
    }

    @SuppressWarnings("unchecked")
    private void registerDynamicEvent(String className, Consumer<Event> handler) {
        try {
            Class<?> rawClass = Class.forName(className);
            if (!Event.class.isAssignableFrom(rawClass)) {
                return;
            }
            Class<? extends Event> eventClass = (Class<? extends Event>) rawClass;
            PluginManager pluginManager = plugin.getServer().getPluginManager();
            pluginManager.registerEvent(eventClass, this, EventPriority.HIGHEST, (listener, event) -> {
                if (eventClass.isInstance(event)) {
                    handler.accept(event);
                }
            }, plugin, true);
        } catch (ClassNotFoundException ignored) {
        }
    }

    private void handleSlimefunRightClick(Event event) {
        Optional<?> slimefunBlock = asOptional(invoke(event, "getSlimefunBlock"));
        if (slimefunBlock.isEmpty()) {
            return;
        }

        Optional<?> clickedBlock = asOptional(invoke(event, "getClickedBlock"));
        if (clickedBlock.isEmpty() || !(clickedBlock.get() instanceof Block block)) {
            return;
        }

        Optional<Object> island = iridium.getIslandViaLocation(block.getLocation());
        if (island.isEmpty()) {
            return;
        }

        Object playerObject = invoke(event, "getPlayer");
        if (!(playerObject instanceof Player player)) {
            return;
        }

        if (iridium.hasPermission(island.get(), player, "OPEN_CONTAINERS")) {
            return;
        }

        Class<?> resultClass = findClass("org.bukkit.event.Event$Result");
        if (resultClass == null) {
            return;
        }
        Object deny = enumConstant(resultClass, "DENY");
        if (deny == null) {
            return;
        }
        invoke(event, "setUseBlock", resultClass, deny);
        invoke(event, "setUseItem", resultClass, deny);
        invoke(event, "cancel");
    }

    private void handleSlimefunMultiBlockInteract(Event event) {
        Object clicked = invoke(event, "getClickedBlock");
        if (!(clicked instanceof Block block)) {
            return;
        }

        Optional<Object> island = iridium.getIslandViaLocation(block.getLocation());
        if (island.isEmpty()) {
            return;
        }

        Object playerObject = invoke(event, "getPlayer");
        if (!(playerObject instanceof Player player)) {
            return;
        }

        if (!iridium.hasPermission(island.get(), player, "OPEN_CONTAINERS")) {
            invoke(event, "setCancelled", boolean.class, true);
        }
    }

    private boolean isHandledExploder(Entity entity) {
        if (entity instanceof Creeper || entity instanceof Wither || entity instanceof WitherSkull || entity instanceof EnderDragon) {
            return true;
        }
        if (entity instanceof Fireball fireball) {
            return fireball.getShooter() instanceof Ghast;
        }
        return false;
    }

    private Block resolveSlimefunBlock(Block block) {
        if (isSlimefunBlock(block)) {
            return block;
        }
        Block above = block.getRelative(0, 1, 0);
        if (isSlimefunBlock(above)) {
            return above;
        }
        Block below = block.getRelative(0, -1, 0);
        if (isSlimefunBlock(below)) {
            return below;
        }
        return null;
    }

    private boolean isSlimefunBlock(Block block) {
        try {
            Object checkResult = slimefunCheckMethod == null ? null : slimefunCheckMethod.invoke(null, block);
            if (checkResult != null) {
                return true;
            }
            Object hasInfoResult = slimefunHasBlockInfoMethod == null ? null : slimefunHasBlockInfoMethod.invoke(null, block);
            return hasInfoResult instanceof Boolean present && present;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void setupSlimefunCheck() {
        try {
            Class<?> blockStorage = Class.forName("me.mrCookieSlime.Slimefun.api.BlockStorage");
            slimefunCheckMethod = blockStorage.getMethod("check", Block.class);
            slimefunHasBlockInfoMethod = blockStorage.getMethod("hasBlockInfo", Block.class);
        } catch (Throwable ignored) {
            slimefunCheckMethod = null;
            slimefunHasBlockInfoMethod = null;
        }
    }

    private boolean isMobGriefPreventionEnabled(Location location) {
        Optional<Object> island = iridium.getIslandViaLocation(location);
        return island.isPresent() && iridium.getEnhancementLevel(island.get(), MOB_GRIEF_ENHANCEMENT_NAME) >= 1;
    }

    private void registerMobGriefUpgrade() {
        if (!iridium.isAvailable()) {
            return;
        }
        try {
            Map<Integer, Object> levels = new HashMap<>();
            levels.put(0, iridium.newEnhancementData(1, 0, Map.of()));
            levels.put(1, iridium.newEnhancementData(1, 500, Map.of()));
            List<String> lore = List.of(
                    "&7Запрещает криперам/гастам/иссушителям ломать блоки.",
                    "&7Также запрещает эндерменам поднимать блоки.",
                    "",
                    "&9&lИнформация:",
                    "&9&l * &7Текущий уровень: &9%current_level%",
                    "&9&l * &7Стоимость улучшения: &9%vault_cost%",
                    "",
                    "&9&lУровни:",
                    "&9&l * &7Уровень 0: &cМобы могут разрушать блоки",
                    "&9&l * &7Уровень 1: &aМобы не могут разрушать блоки",
                    "",
                    "&9&l[!] &9ЛКМ — купить уровень %next_level%."
            );
            Object item = iridium.newItem("CREEPER_HEAD", 1, "&9&lЗащита от грифа мобов", lore);
            iridium.setItemSlot(item, 15);
            Object enhancement = iridium.newEnhancement(true, "UPGRADE", item, levels);
            iridium.addEnhancement(MOB_GRIEF_ENHANCEMENT_NAME, enhancement);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to register merged mob grief enhancement", ex);
        }
    }

    private static Optional<?> asOptional(Object value) {
        return value instanceof Optional<?> optional ? optional : Optional.empty();
    }

    private static Class<?> findClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumConstant(Class<?> enumClass, String name) {
        if (enumClass == null || !enumClass.isEnum()) {
            return null;
        }
        return Enum.valueOf((Class<? extends Enum>) enumClass, name);
    }

    private static Object invoke(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object invoke(Object target, String methodName, Class<?> parameterType, Object arg) {
        try {
            Method method = target.getClass().getMethod(methodName, parameterType);
            method.setAccessible(true);
            return method.invoke(target, arg);
        } catch (Exception ignored) {
            return null;
        }
    }
}
