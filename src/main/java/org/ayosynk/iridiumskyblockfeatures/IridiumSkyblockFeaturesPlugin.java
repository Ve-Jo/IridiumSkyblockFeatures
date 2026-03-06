package org.ayosynk.iridiumskyblockfeatures;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class IridiumSkyblockFeaturesPlugin extends JavaPlugin {

    private NamespacedKey npcTypeKey;
    private NamespacedKey islandKeyKey;
    private IslandWorldFeatureSupport islandWorldFeatureSupport;
    private MergedHooksSupport mergedHooksSupport;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        npcTypeKey = new NamespacedKey(this, "npc_type");
        islandKeyKey = new NamespacedKey(this, "island_key");

        Plugin iridiumPlugin = Bukkit.getPluginManager().getPlugin("IridiumSkyblock");
        if (iridiumPlugin == null || !iridiumPlugin.isEnabled()) {
            getLogger().severe("IridiumSkyblock не найден или не включен. Отключаю плагин.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (Bukkit.getPluginManager().getPlugin("FancyHolograms") == null) {
            getLogger().severe("FancyHolograms не найден. Отключаю плагин.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        IridiumBridge iridiumBridge = new IridiumBridge(iridiumPlugin);
        islandWorldFeatureSupport = new IslandWorldFeatureSupport(this, npcTypeKey, islandKeyKey, iridiumBridge);
        mergedHooksSupport = new MergedHooksSupport(this, npcTypeKey);

        islandWorldFeatureSupport.enable();
        mergedHooksSupport.enable();
    }

    @Override
    public void onDisable() {
        if (islandWorldFeatureSupport != null) {
            islandWorldFeatureSupport.disable();
        }
    }
}
