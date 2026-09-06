# MiraBackpacks

MiraBackpacks provides unique physical backpack items for the Mira Paper server suite.

Every backpack is its own persistent object with a permanent UUID. Storage belongs to the backpack item, not to a player account. Whoever physically possesses the backpack can use the same stored contents.

## Download

[**Download MiraBackpacks v0.2.0**](https://github.com/FiveSOCE/Mira-Backpacks/releases/download/v0.2.0/MiraBackpacks-0.2.0.jar)

SHA-256: `f27499a601ad4bd5796252bc0792aa61eb3b16488bbf146feb66fb7d7e3e47fa`

## Requirements

- Paper 1.21.11
- Java 21
- MiraCore 0.5.1+

## Physical backpack model

Each backpack item stores MiraBackpacks PDC identity:

- backpack marker
- permanent backpack UUID
- backpack tier

The item must remain in the player's off hand while the backpack is open. Removing or swapping it closes the session.

Backpack storage is persisted by backpack UUID in `plugins/MiraBackpacks/backpacks.yml`.

There is no permanent owner binding. Possession is ownership:

- a backpack drops naturally with normal player death loot
- another player may pick it up
- that player can then open the same backpack
- its original contents follow the backpack UUID

## Tiers

| Tier | Slots | Default CustomModelData |
| --- | ---: | ---: |
| Small | 9 | 20001 |
| Medium | 18 | 20002 |
| Large | 27 | 20003 |
| Elite | 36 | 20004 |
| Mythic | 45 | 20005 |
| Godly | 54 | 20006 |

The base item defaults to `LEATHER`. Material, display name and model data are configurable per tier in `config.yml`.

CustomModelData is cosmetic only. Real backpack identity comes from PDC.

## Access

- Put the backpack in the off hand.
- Right-click with the off hand backpack, or run `/backpack`.
- The same backpack must remain in the off hand while the inventory is open.
- Staff UUID edit sessions are exempt from the off-hand requirement.

## Safety

- Backpacks cannot be stored inside backpacks.
- Click, shift-click and drag nesting attempts are blocked.
- Defensive save logic strips nested backpacks if another plugin bypasses normal inventory events.
- Only one editable session can exist for a backpack UUID at a time.
- Duplicate loaded backpack UUIDs are audited through MiraCore.
- Normal player access is blocked when multiple loaded physical copies of the same UUID are detected.
- Recovery refuses to recreate a backpack while another matching physical instance is visible in loaded player inventories or as a loaded dropped item.

## Commands

| Command | Permission | Purpose |
| --- | --- | --- |
| `/backpack` | `mirabackpacks.use` | Opens the valid backpack currently in your off hand. |
| `/bp` | `mirabackpacks.use` | Alias. |
| `/backpack give <player> <tier> [amount]` | `mirabackpacks.admin` | Creates brand new backpacks with unique UUIDs. |
| `/backpack inspect <uuid>` | `mirabackpacks.admin.inspect` | Read-only storage inspection. |
| `/backpack edit <uuid>` | `mirabackpacks.admin.edit` | Audited UUID storage edit. |
| `/backpack status <uuid>` | `mirabackpacks.admin.inspect` | Shows tier, capacity, usage, loaded copies and active editor. |
| `/backpack recover <uuid> <player>` | `mirabackpacks.admin.recover` | Recreates a stored backpack only when no loaded physical copy is detected. |
| `/backpack reload` | `mirabackpacks.admin` | Reloads tier/model configuration. |

## Resource-pack integration

The plugin already emits tier-specific CustomModelData IDs. A resource pack can assign a different backpack model to each tier without changing ordinary leather.

Normal leather has no Mira backpack PDC and no Mira model ID, so it remains ordinary leather. MiraBackpacks functionality never trusts CustomModelData as item identity.

## API

`BackpacksApi` is registered through Bukkit ServicesManager and MiraCore. It supports:

- backpack identification
- creation
- used-slot lookup
- defensive content reads
- content writes with anti-nesting validation

## Release status

v0.2.0 passed repository CI and the GitHub release asset has been verified. Server behavior still requires real Paper 1.21.11 gameplay testing before being considered server-confirmed.
