# MiraBackpacks

MiraBackpacks is the storage and identity backend for the MiraEnchantments **Backpack** chestplate enchant.

## Download

[**Download MiraBackpacks v0.3.1**](https://github.com/FiveSOCE/Mira-Backpacks/releases/download/v0.3.1/MiraBackpacks-0.3.1.jar)

SHA-256: `c6249ac84cf60997376ee37a60e463017dfc12359cd9bb4d9e51df6c28ba8e5b`

## Requirements

- Paper 1.21.11
- Java 21
- MiraCore 0.5.1+
- Designed to be paired with MiraEnchantments 0.4.9+

## Architecture

MiraBackpacks is the brain behind backpack storage.

MiraEnchantments owns the Backpack rune and enchant application. When Backpack is first applied to a chestplate, MiraEnchantments calls the MiraBackpacks API. MiraBackpacks assigns that chestplate a permanent backpack UUID and creates the backing storage.

The backpack UUID belongs to the chestplate, not a player.

- Backpack chestplate dies with a player -> the chestplate drops normally.
- Another player picks it up and equips it -> they gain access to the same stored backpack.
- A different Backpack chestplate has a different UUID and different contents.
- There is no permanent owner binding. Possession is ownership.

## Access

Players use:

`/backpack`

MiraBackpacks checks the currently equipped chestplate.

Access requires:
1. a MiraBackpacks backpack UUID linked to the chestplate, and
2. the actual MiraEnchantments Backpack enchant currently present on that chestplate.

The same linked chestplate must remain equipped while the backpack GUI is open. Removing/swapping it closes the GUI.

If the Backpack enchant is removed, the UUID and backing storage are retained but inaccessible. Reapplying Backpack to that same chestplate restores access to the same UUID/storage.

## Capacity

Backpack level controls visible capacity:

| Enchant | Slots |
| --- | ---: |
| Backpack I | 9 |
| Backpack II | 18 |
| Backpack III | 27 |
| Backpack IV | 36 |
| Backpack V | 45 |
| Backpack VI | 54 |

Storage is non-destructive. If a Backpack VI chestplate is later downgraded to Backpack III, slots above 27 remain persisted and hidden rather than being deleted. Re-upgrading exposes them again.

## Safety

- Permanent UUID per linked chestplate.
- Duplicate loaded UUIDs are audited through MiraCore.
- Player access is blocked when duplicate linked UUIDs are detected.
- One editable session per backpack UUID.
- Backpack-linked chestplates cannot be stored inside another backpack.
- Staff inspection/editing is UUID-based and audited.
- Recovery is blocked while another linked physical copy is detected in loaded player inventories.
- Storage persists independently of player UUIDs.

## Commands

| Command | Permission | Purpose |
| --- | --- | --- |
| `/backpack` | `mirabackpacks.use` | Opens the Backpack enchant storage on the currently worn chestplate. |
| `/bp` | `mirabackpacks.use` | Alias. |
| `/backpack inspect <uuid>` | `mirabackpacks.admin.inspect` | Read-only UUID storage inspection. |
| `/backpack edit <uuid>` | `mirabackpacks.admin.edit` | Opens an audited editable UUID storage session. |
| `/backpack status <uuid>` | `mirabackpacks.admin.inspect` | Shows level, capacity, storage and duplicate state. |
| `/backpack recover <uuid> <player>` | `mirabackpacks.admin.recover` | Links orphaned storage to the target's currently worn unlinked chestplate when no loaded copy exists. |

## API

`BackpacksApi` is registered through Bukkit ServicesManager and MiraCore.

MiraEnchantments uses `ensureLinked(chestplate, level)` whenever Backpack is applied/upgraded. The API also exposes backpack identity, active-enchant identity, content reads/writes and used-slot lookup.

## Release status

v0.3.1 passed CI and its GitHub release asset is verified. It still requires real Paper gameplay testing before being marked server-confirmed.
