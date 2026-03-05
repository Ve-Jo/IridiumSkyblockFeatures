# IridiumSkyblockFeatures

Custom Bukkit/Purpur addon for **IridiumSkyblock** focused on island NPCs and hologram-driven island information.

## Features

- Creates and maintains a spawn villager NPC for island-related interaction
- Creates and maintains per-island villager NPCs for players on their islands
- Uses persistent NPC metadata to distinguish spawn and island NPC types
- Supports FancyHolograms for NPC hologram text
- Supports optional island border holograms around the current player's island
- Periodically refreshes island NPCs and border holograms for online players
- Uses IridiumSkyblock placeholders in hologram lines for island stats and ownership details
- Reuses existing NPCs where possible and cleans up duplicate NPC instances by type/location

## Requirements

- Bukkit/Paper/Purpur-compatible server
- `IridiumSkyblock`
- `FancyHolograms`
- Java 21 for building this project

## Installation

1. Build the plugin jar with Gradle.
2. Install `IridiumSkyblock` and `FancyHolograms` on the server.
3. Put the jar into the server `plugins` folder.
4. Start the server once to generate `config.yml`.
5. Adjust NPC spawn positions, hologram settings, and skyblock world names.
6. Restart the server.

## Downloads

- Repository: `https://github.com/Ve-Jo/IridiumSkyblockFeatures`
- Releases: `https://github.com/Ve-Jo/IridiumSkyblockFeatures/releases`

If a release is published, you can download the jar directly from GitHub Releases instead of building the plugin yourself.

## Building

```bash
./gradlew build
```

## Configuration

Configuration file:

- `src/main/resources/config.yml`

### Main sections

- `spawn-villager`
  - enable/disable the global spawn villager
  - configure world, coordinates, name, and optional hologram

### Example spawn villager setup

```yaml
spawn-villager:
  enabled: true
  world: worlds
  x: 0.5
  y: 65.0
  z: 0.5
  yaw: 0.0
  pitch: 0.0
  name: 'Менеджер острова'
  hologram:
    enabled: false
    y-offset: 2.7
```

- `skyblock-worlds`
  - list of worlds treated as IridiumSkyblock worlds by this addon

### Example island villager setup

```yaml
island-villager:
  enabled: true
  name: 'Менеджер острова'
  y-offset: 0.0
  hologram:
    enabled: true
    y-offset: 2.7
```

- `island-border-holograms`
  - enable/disable island border holograms
  - configure border size fallback, offsets, and refresh interval

### Example island border holograms

```yaml
island-border-holograms:
  enabled: false
  default-size: 200.0
  player-relative-y: true
  player-y-offset: 1.8
  y: 74.0
  outside-offset: 0.8
  update-seconds: 30
```

- `text.villager-hologram-lines`
  - hologram lines shown above villager NPCs

- `text.border-hologram-lines`
  - hologram lines shown near island border markers

### Example hologram text

```yaml
text:
  villager-hologram-lines:
    - '&e&lМенеджер острова'
    - '&7Остров: &a%iridiumskyblock_island_name% &8(&7Владелец: &f%iridiumskyblock_island_owner%&8)'
    - '&7Уровень: &a%iridiumskyblock_island_level% &8• &7Опыт: &a%iridiumskyblock_island_experience%'
  border-hologram-lines:
    - '{name}'
    - 'Ур. {level} • {value}'
```

## Runtime Behavior

### Spawn villager

On startup, the plugin:

- validates that `IridiumSkyblock` is present and enabled
- validates that `FancyHolograms` is present
- finds existing tagged NPCs if they already exist
- ensures the configured spawn villager is present

### Island villager management

For players in configured skyblock worlds, the plugin attempts to:

- resolve island information from IridiumSkyblock
- determine a location for the island villager
- reuse or spawn the villager
- create/update the hologram above that villager

### Border holograms

If enabled, the plugin periodically refreshes player-specific island border holograms based on resolved border information.

Current limitation:
- Border hologram placement is not fully finished yet.
- The placement logic is currently reliable only when the island trader location, and therefore the practical `/island sethome` center point as well, is positioned in the center of the island.
- If your trader/home is offset from the true center, the holograms may be calculated correctly in distance but appear in imperfect positions around the island border.

## Dependencies

Hard dependencies declared in `plugin.yml`:

- `IridiumSkyblock`
- `FancyHolograms`

## Development Notes

- Main class: `org.ayosynk.iridiumskyblockfeatures.IridiumSkyblockFeaturesPlugin`
- Plugin name: `IridiumSkyblockFeatures`
- Designed as a focused addon rather than a general-purpose skyblock framework
