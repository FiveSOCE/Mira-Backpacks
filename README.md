# MiraBackpacks

MiraBackpacks provides persistent personal storage for the Mira Paper server suite. Each player receives a backpack inventory with permission-based capacity, non-destructive persistence and audited staff inspection/editing.

## Download

[**Download MiraBackpacks v0.1.1**](https://github.com/FiveSOCE/Mira-Backpacks/releases/download/v0.1.1/MiraBackpacks-0.1.1.jar)

[View All Releases](https://github.com/FiveSOCE/Mira-Backpacks/releases)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- MiraCore 0.2.0 or newer

## How MiraBackpacks Works

Each player has persistent virtual backpack storage. Exact Bukkit ItemStacks are cloned and serialized, preserving material, amount, display name, lore, enchantments, PDC, custom model data and other metadata.

Supported permission tiers are 18, 27, 36, 45 and 54 slots. v0.1.1 deliberately makes backpack capacity non-destructive: once stored capacity is larger than a player's current permission tier, opening the backpack never silently truncates hidden slots. A rank/permission downgrade therefore cannot destroy items. Visible saves merge back into the existing backing array rather than replacing unseen tail slots.

Only one editable session may exist for a backpack at a time. This prevents the owner and an administrator from opening competing writable copies and overwriting each other's changes. Staff inspection is read-only; explicit staff editing uses a separate audited command.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/backpack` | `mirabackpacks.use` | Opens your personal editable backpack. |
| `/bp` | `mirabackpacks.use` | Alias for `/backpack`. |
| `/backpack inspect <player>` | `mirabackpacks.inspect` | Opens a read-only view of another player's backpack. |
| `/backpack edit <player>` | `mirabackpacks.edit` | Opens an audited editable staff session for another player's backpack. |
| `/backpack status <player>` | `mirabackpacks.inspect` | Shows capacity, used slots, persisted size and active editor state. |

## Permissions

| Permission | Default | What it does |
| --- | --- | --- |
| `mirabackpacks.use` | Everyone | Allows opening your backpack. |
| `mirabackpacks.inspect` | OP | Allows read-only inspection/status. |
| `mirabackpacks.edit` | OP | Allows audited editing of another player's backpack. |
| `mirabackpacks.size.18` | Everyone | Grants an 18-slot backpack tier. |
| `mirabackpacks.size.27` | No | Grants a 27-slot backpack tier. |
| `mirabackpacks.size.36` | No | Grants a 36-slot backpack tier. |
| `mirabackpacks.size.45` | No | Grants a 45-slot backpack tier. |
| `mirabackpacks.size.54` | No | Grants a 54-slot backpack tier. |

## API / Integration

`BackpacksApi` is registered through Bukkit ServicesManager and MiraCore. It exposes configured capacity, used slots and defensive-copy content read/write methods. Integrations should use this API instead of reading `backpacks.yml` directly.

Administrative inspection/edit actions are recorded in MiraCore audit history.

## Persistence

Storage is saved to `plugins/MiraBackpacks/backpacks.yml`. ItemStack serialization preserves custom Mira item metadata, including PDC.

## Building

```bash
gradle clean build
```

The output JAR is created in `build/libs/`.
