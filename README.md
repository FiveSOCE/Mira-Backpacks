# MiraBackpacks

MiraBackpacks provides persistent personal storage for the Mira Paper server suite. Each player receives a backpack inventory whose size is controlled by permissions, with administrative inspection support for staff.

## Download

[**Download MiraBackpacks v0.1.0**](https://github.com/FiveSOCE/Mira-Backpacks/releases/download/v0.1.0/MiraBackpacks-0.1.0.jar)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21

## How MiraBackpacks Works

Each player has a persistent backpack inventory. ItemStack metadata is preserved through Bukkit serialization, so enchanted items, custom names, lore and other item metadata survive saves and restarts. The player's effective backpack size is determined by the highest size permission they have, with supported tiers of 18, 27, 36, 45 and 54 slots.

Staff with inspection access can open another player's backpack for moderation or support. MiraBackpacks also exposes a public Bukkit ServicesManager API for other Mira systems.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/backpack` | `mirabackpacks.use` | Opens your personal backpack. |
| `/bp` | `mirabackpacks.use` | Alias for `/backpack`. |
| `/backpack inspect <player>` | `mirabackpacks.inspect` | Opens another player's backpack for staff inspection. |

## Permissions

| Permission | Default | What it does |
| --- | --- | --- |
| `mirabackpacks.use` | Everyone | Allows opening your backpack. |
| `mirabackpacks.inspect` | OP | Allows inspecting another player's backpack. |
| `mirabackpacks.size.18` | Everyone | Grants an 18-slot backpack tier. |
| `mirabackpacks.size.27` | No | Grants a 27-slot backpack tier. |
| `mirabackpacks.size.36` | No | Grants a 36-slot backpack tier. |
| `mirabackpacks.size.45` | No | Grants a 45-slot backpack tier. |
| `mirabackpacks.size.54` | No | Grants a 54-slot backpack tier. |
