# MiraBackpacks

MiraBackpacks is the permanent chestplate-linked inventory backend used by the MiraEnchantments **Backpack** enchant. Each qualifying chestplate owns its own UUID-backed, persistent storage, with six capacity levels, duplicate safety, read-only staff inspection and audited recovery.

## Versioned JAR downloads

| Paper server target | Java | Final JAR | Direct asset |
| --- | --- | --- | --- |
| **1.18.2** | 17 | `MiraBackpacks_1.18.2.jar` | [Legacy JAR](https://github.com/FiveSOCE/Mira-Backpacks/releases/download/dev-1.18.2/MiraBackpacks_1.18.2.jar) |
| **1.21.11–26.2** | 21 baseline; modern server may require Java 25 | `MiraBackpacks_1.21.11-26.2.jar` | [Modern JAR](https://github.com/FiveSOCE/Mira-Backpacks/releases/download/compat-modern-1.21.11-26.2/MiraBackpacks_1.21.11-26.2.jar) |

Download links become usable once the corresponding CI run succeeds and publishes the release asset. **Paper 1.19–1.21.10 and in-game feature parity have not been certified.** The server must use the matching version of MiraCore and should not load both Backpacks JARs simultaneously.

[Legacy source](https://github.com/FiveSOCE/Mira-Backpacks/tree/compat/1.18.2) · [Modern source](https://github.com/FiveSOCE/Mira-Backpacks) · [All releases](https://github.com/FiveSOCE/Mira-Backpacks/releases)

## Dependencies

- **Required:** MiraCore, compatible with the selected Paper version.
- **Required to make Backpack-enchanted chestplates:** MiraEnchantments, with a matching API version. Backpacks can compile independently, but normal player use depends on the enchant integration.
- **Paper:** 1.18.2 for the legacy JAR, or modern 1.21.11–26.2 as the validated build target.
- **Runtime:** Java 17 for legacy or Java 21+ as required by the modern server.

## Permissions

| Node | Default | Purpose |
| --- | --- | --- |
| `mirabackpacks.use` | Everyone | Open personal equipped Backpack storage |
| `mirabackpacks.admin.inspect` | OP | Inspect and view storage state |
| `mirabackpacks.admin.edit` | OP | Edit backpack storage by UUID |
| `mirabackpacks.admin.recover` | OP | Recover orphaned backpack identity |

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
