package org.ayosynk.iridiumskyblockfeatures;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

final class MergedHooksIridiumAccess {

    private final JavaPlugin plugin;
    private final Method apiGetInstance;
    private final Method apiAddEnhancement;
    private final Method apiGetIslandViaLocation;
    private final Method apiGetUser;
    private final Method apiGetIslandPermission;
    private final Constructor<?> enhancementDataConstructor;
    private final Constructor<?> itemConstructor;
    private final Constructor<?> enhancementConstructor;
    private final Field itemSlotField;
    private final Method iridiumGetInstance;
    private final Method getTeamManager;
    private final Method getTeamEnhancement;
    private final Method getTeamEnhancementLevel;
    private final Class<?> xMaterialClass;
    private final Class<?> enhancementTypeClass;
    private final Class<?> permissionTypeClass;
    private final boolean available;

    MergedHooksIridiumAccess(JavaPlugin plugin) {
        this.plugin = plugin;
        Method localApiGetInstance = null;
        Method localApiAddEnhancement = null;
        Method localApiGetIslandViaLocation = null;
        Method localApiGetUser = null;
        Method localApiGetIslandPermission = null;
        Constructor<?> localEnhancementDataConstructor = null;
        Constructor<?> localItemConstructor = null;
        Constructor<?> localEnhancementConstructor = null;
        Field localItemSlotField = null;
        Method localIridiumGetInstance = null;
        Method localGetTeamManager = null;
        Method localGetTeamEnhancement = null;
        Method localGetTeamEnhancementLevel = null;
        Class<?> localXMaterialClass = null;
        Class<?> localEnhancementTypeClass = null;
        Class<?> localPermissionTypeClass = null;
        boolean localAvailable = false;
        try {
            Class<?> apiClass = Class.forName("com.iridium.iridiumskyblock.api.IridiumSkyblockAPI");
            Class<?> enhancementDataClass = Class.forName("com.iridium.iridiumskyblock.dependencies.iridiumteams.enhancements.EnhancementData");
            Class<?> itemClass = Class.forName("com.iridium.iridiumskyblock.dependencies.iridiumcore.Item");
            Class<?> enhancementClass = Class.forName("com.iridium.iridiumskyblock.dependencies.iridiumteams.enhancements.Enhancement");
            localEnhancementTypeClass = Class.forName("com.iridium.iridiumskyblock.dependencies.iridiumteams.enhancements.EnhancementType");
            localXMaterialClass = Class.forName("com.iridium.iridiumskyblock.dependencies.xseries.XMaterial");
            localPermissionTypeClass = Class.forName("com.iridium.iridiumskyblock.dependencies.iridiumteams.PermissionType");
            Class<?> iridiumClass = Class.forName("com.iridium.iridiumskyblock.IridiumSkyblock");
            Class<?> teamEnhancementClass = Class.forName("com.iridium.iridiumskyblock.dependencies.iridiumteams.database.TeamEnhancement");

            localApiGetInstance = apiClass.getMethod("getInstance");
            localApiAddEnhancement = apiClass.getMethod("addEnhancement", String.class, enhancementClass);
            localApiGetIslandViaLocation = apiClass.getMethod("getIslandViaLocation", Location.class);
            localApiGetUser = apiClass.getMethod("getUser", Class.forName("org.bukkit.OfflinePlayer"));
            localApiGetIslandPermission = findMethod(apiClass, "getIslandPermission", 3);
            localEnhancementDataConstructor = enhancementDataClass.getConstructor(int.class, int.class, Map.class);
            localItemConstructor = itemClass.getConstructor(localXMaterialClass, int.class, String.class, List.class);
            localEnhancementConstructor = enhancementClass.getConstructor(boolean.class, localEnhancementTypeClass, itemClass, Map.class);
            localItemSlotField = itemClass.getField("slot");
            localIridiumGetInstance = iridiumClass.getMethod("getInstance");
            localGetTeamManager = iridiumClass.getMethod("getTeamManager");
            localGetTeamEnhancement = findMethod(localGetTeamManager.getReturnType(), "getTeamEnhancement", 2);
            localGetTeamEnhancementLevel = teamEnhancementClass.getMethod("getLevel");
            localAvailable = true;
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to initialize Iridium reflection bridge for merged hooks", ex);
        }
        this.apiGetInstance = localApiGetInstance;
        this.apiAddEnhancement = localApiAddEnhancement;
        this.apiGetIslandViaLocation = localApiGetIslandViaLocation;
        this.apiGetUser = localApiGetUser;
        this.apiGetIslandPermission = localApiGetIslandPermission;
        this.enhancementDataConstructor = localEnhancementDataConstructor;
        this.itemConstructor = localItemConstructor;
        this.enhancementConstructor = localEnhancementConstructor;
        this.itemSlotField = localItemSlotField;
        this.iridiumGetInstance = localIridiumGetInstance;
        this.getTeamManager = localGetTeamManager;
        this.getTeamEnhancement = localGetTeamEnhancement;
        this.getTeamEnhancementLevel = localGetTeamEnhancementLevel;
        this.xMaterialClass = localXMaterialClass;
        this.enhancementTypeClass = localEnhancementTypeClass;
        this.permissionTypeClass = localPermissionTypeClass;
        this.available = localAvailable;
    }

    boolean isAvailable() {
        return available;
    }

    Optional<Object> getIslandViaLocation(Location location) {
        if (!available || location == null) {
            return Optional.empty();
        }
        try {
            Object api = getApiInstance();
            if (api == null) {
                return Optional.empty();
            }
            Object result = apiGetIslandViaLocation.invoke(api, location);
            if (result instanceof Optional<?> optional) {
                if (optional.isPresent()) {
                    return Optional.of(optional.get());
                }
                return Optional.empty();
            }
            return Optional.ofNullable(result);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    boolean hasPermission(Object island, Player player, String permissionTypeName) {
        if (!available || island == null || apiGetIslandPermission == null) {
            return true;
        }
        try {
            Object api = getApiInstance();
            if (api == null) {
                return true;
            }
            Object user = apiGetUser.invoke(api, player);
            Object permissionType = enumConstant(permissionTypeClass, permissionTypeName);
            if (user == null || permissionType == null) {
                return true;
            }
            Object result = apiGetIslandPermission.invoke(api, island, user, permissionType);
            return !(result instanceof Boolean allowed) || allowed;
        } catch (Exception ignored) {
            return true;
        }
    }

    int getEnhancementLevel(Object island, String enhancementName) {
        if (!available || island == null || getTeamEnhancement == null) {
            return 0;
        }
        try {
            Object iridium = getIridiumInstance();
            if (iridium == null) {
                return 0;
            }
            Object teamManager = getTeamManager.invoke(iridium);
            Object enhancement = getTeamEnhancement.invoke(teamManager, island, enhancementName);
            Object level = getTeamEnhancementLevel.invoke(enhancement);
            return level instanceof Number number ? number.intValue() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    Object newEnhancementData(int minLevel, int money, Map<String, Double> bankCosts) throws Exception {
        return enhancementDataConstructor.newInstance(minLevel, money, bankCosts);
    }

    Object newItem(String materialName, int amount, String displayName, List<String> lore) throws Exception {
        return itemConstructor.newInstance(enumConstant(xMaterialClass, materialName), amount, displayName, lore);
    }

    void setItemSlot(Object item, int slot) throws Exception {
        itemSlotField.set(item, slot);
    }

    Object newEnhancement(boolean enabled, String typeName, Object item, Map<Integer, Object> levels) throws Exception {
        return enhancementConstructor.newInstance(enabled, enumConstant(enhancementTypeClass, typeName), item, levels);
    }

    void addEnhancement(String name, Object enhancement) throws Exception {
        Object api = getApiInstance();
        if (api == null) {
            return;
        }
        apiAddEnhancement.invoke(api, name, enhancement);
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        return null;
    }

    private Object getApiInstance() throws Exception {
        return apiGetInstance.invoke(null);
    }

    private Object getIridiumInstance() throws Exception {
        return iridiumGetInstance.invoke(null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumConstant(Class<?> enumClass, String name) {
        if (enumClass == null || !enumClass.isEnum()) {
            return null;
        }
        return Enum.valueOf((Class<? extends Enum>) enumClass, name);
    }
}
