package org.ayosynk.iridiumskyblockfeatures;

import org.bukkit.Location;

import java.util.List;
import java.util.UUID;

record BorderInfo(Location center, double size) {}

record IslandInfo(String key, String name, int level, double value, Location homeLocation) {}

final class IslandRuntime {
    UUID villagerUuid;
}

final class PlayerBorderRuntime {
    List<String> hologramNames;
}
