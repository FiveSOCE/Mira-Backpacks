package gg.mira.backpacks;

import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import com.mira.core.api.ModuleHealth;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class MiraBackpacksPlugin extends JavaPlugin implements Listener, TabExecutor {
    private MiraCore core;
    private BackpackService service;
    private final Map<UUID, UUID> activeEditors = new HashMap<>();

    @Override
    public void onEnable() {
        core = MiraCoreProvider.require();
        service = new BackpackService(this);

        getServer().getServicesManager().register(BackpacksApi.class, service, this, ServicePriority.Normal);
        core.services().register(BackpacksApi.class, service);
        core.modules().register(this, "MiraBackpacks");
        core.modules().setHealth(this, ModuleHealth.HEALTHY,
                "Persistent non-destructive backpack storage and audited administration ready");

        getServer().getPluginManager().registerEvents(this, this);
        var command = getCommand("backpack");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        if (service != null) service.save();
        activeEditors.clear();
        getServer().getServicesManager().unregisterAll(this);
        if (core != null) {
            if (service != null) core.services().unregister(BackpacksApi.class, service);
            core.modules().unregister(this);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "&cPlayers only.");
            return true;
        }

        if (args.length == 0) {
            return openEditable(player, player.getUniqueId(), player.getName(), service.capacityFor(player.getUniqueId(), player), false);
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("inspect") || action.equals("edit") || action.equals("status")) {
            String required = action.equals("edit") ? "mirabackpacks.edit" : "mirabackpacks.inspect";
            if (!player.hasPermission(required)) {
                msg(player, "&cYou do not have permission.");
                return true;
            }
            if (args.length < 2) {
                msg(player, "&eUsage: /backpack " + action + " <player>");
                return true;
            }

            OfflinePlayer target = resolve(args[1]);
            if (target == null) {
                msg(player, "&cPlayer not found.");
                return true;
            }
            int capacity = service.capacityFor(target.getUniqueId(), target.getPlayer());

            if (action.equals("status")) {
                int used = service.usedSlots(target.getUniqueId());
                msg(player, "&6Backpack Status &7- &f" + displayName(target));
                msg(player, "&7Capacity: &f" + capacity + " &7Used: &f" + used
                        + " &7Stored slots: &f" + service.storedSize(target.getUniqueId()));
                UUID editor = activeEditors.get(target.getUniqueId());
                msg(player, "&7Active editor: &f" + (editor == null ? "None" : displayName(Bukkit.getOfflinePlayer(editor))));
                return true;
            }

            if (action.equals("inspect")) {
                player.openInventory(service.createInventory(target.getUniqueId(), displayName(target), capacity, AccessMode.READ_ONLY));
                audit(player, "BACKPACK_INSPECTED", target.getUniqueId(), Map.of("targetName", displayName(target)));
                return true;
            }

            return openEditable(player, target.getUniqueId(), displayName(target), capacity, true);
        }

        msg(player, "&eUsage: /backpack [inspect|edit|status] [player]");
        return true;
    }

    private boolean openEditable(Player viewer, UUID owner, String ownerName, int capacity, boolean adminEdit) {
        UUID existing = activeEditors.get(owner);
        if (existing != null && !existing.equals(viewer.getUniqueId())) {
            msg(viewer, "&cThat backpack is already open for editing by &f"
                    + displayName(Bukkit.getOfflinePlayer(existing)) + "&c.");
            return true;
        }
        activeEditors.put(owner, viewer.getUniqueId());
        viewer.openInventory(service.createInventory(owner, ownerName, capacity, AccessMode.EDITABLE));
        if (adminEdit) {
            audit(viewer, "BACKPACK_EDIT_OPENED", owner, Map.of("targetName", ownerName));
        }
        return true;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BackpackHolder holder)) return;
        if (holder.mode() == AccessMode.READ_ONLY) event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BackpackHolder holder)) return;
        if (holder.mode() != AccessMode.READ_ONLY) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackpackHolder holder)) return;
        if (holder.mode() == AccessMode.READ_ONLY) return;

        service.storeVisible(holder.owner(), event.getInventory().getContents());
        activeEditors.remove(holder.owner(), event.getPlayer().getUniqueId());

        if (!holder.owner().equals(event.getPlayer().getUniqueId())) {
            core.audit().record("MiraBackpacks", "BACKPACK_EDIT_SAVED", event.getPlayer().getUniqueId(),
                    event.getPlayer().getName(), holder.owner().toString(), "Saved administrative backpack edit",
                    Map.of("targetName", holder.ownerName(), "visibleSize", Integer.toString(event.getInventory().getSize())));
        }
    }

    private void audit(Player actor, String action, UUID target, Map<String, String> metadata) {
        core.audit().record("MiraBackpacks", action, actor.getUniqueId(), actor.getName(), target.toString(), action, metadata);
    }

    private OfflinePlayer resolve(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Bukkit.getOfflinePlayer(UUID.fromString(raw)); }
        catch (IllegalArgumentException ignored) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(raw);
            return player.getName() != null || player.hasPlayedBefore() || player.isOnline() ? player : null;
        }
    }

    private String displayName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private void msg(CommandSender sender, String raw) { core.messages().send(sender, raw); }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>();
            if (sender.hasPermission("mirabackpacks.inspect")) values.addAll(List.of("inspect", "status"));
            if (sender.hasPermission("mirabackpacks.edit")) values.add("edit");
            return complete(args[0], values);
        }
        if (args.length == 2 && Set.of("inspect", "edit", "status").contains(args[0].toLowerCase(Locale.ROOT))) {
            return complete(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        return List.of();
    }

    private static List<String> complete(String prefix, Collection<String> values) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .distinct().sorted().toList();
    }

    public enum AccessMode { EDITABLE, READ_ONLY }

    public interface BackpacksApi {
        int configuredSize(UUID player);
        int usedSlots(UUID player);
        ItemStack[] contents(UUID player);
        void setContents(UUID player, ItemStack[] contents);
    }

    public record BackpackHolder(UUID owner, String ownerName, AccessMode mode) implements InventoryHolder {
        @Override public Inventory getInventory() { return Bukkit.createInventory(this, 9, "Backpack"); }
    }

    public static final class BackpackService implements BackpacksApi {
        private final MiraBackpacksPlugin plugin;
        private final File file;
        private final Map<UUID, ItemStack[]> storage = new HashMap<>();

        BackpackService(MiraBackpacksPlugin plugin) {
            this.plugin = plugin;
            this.file = new File(plugin.getDataFolder(), "backpacks.yml");
            load();
        }

        int permissionSize(Player player) {
            if (player == null) return 18;
            for (int size : new int[]{54, 45, 36, 27, 18}) {
                if (player.hasPermission("mirabackpacks.size." + size)) return size;
            }
            return 18;
        }

        int capacityFor(UUID owner, Player onlineOwner) {
            int stored = storedSize(owner);
            int permission = permissionSize(onlineOwner);
            int capacity = Math.max(18, Math.max(stored, permission));
            return Math.min(54, ((capacity + 8) / 9) * 9);
        }

        int storedSize(UUID owner) {
            return storage.getOrDefault(owner, new ItemStack[0]).length;
        }

        @Override
        public int configuredSize(UUID player) {
            Player online = Bukkit.getPlayer(player);
            return capacityFor(player, online);
        }

        @Override
        public int usedSlots(UUID player) {
            int used = 0;
            for (ItemStack item : storage.getOrDefault(player, new ItemStack[0])) {
                if (item != null && !item.getType().isAir()) used++;
            }
            return used;
        }

        Inventory createInventory(UUID owner, String name, int size, AccessMode mode) {
            int safeSize = Math.max(9, Math.min(54, ((size + 8) / 9) * 9));
            BackpackHolder holder = new BackpackHolder(owner, name, mode);
            Inventory inventory = Bukkit.createInventory(holder, safeSize,
                    "§8" + name + "'s Backpack" + (mode == AccessMode.READ_ONLY ? " §7[View]" : ""));
            ItemStack[] existing = storage.getOrDefault(owner, new ItemStack[0]);
            for (int i = 0; i < Math.min(existing.length, safeSize); i++) {
                if (existing[i] != null) inventory.setItem(i, existing[i].clone());
            }
            return inventory;
        }

        synchronized void storeVisible(UUID owner, ItemStack[] visible) {
            ItemStack[] existing = storage.getOrDefault(owner, new ItemStack[0]);
            int resultSize = Math.max(existing.length, visible.length);
            ItemStack[] result = cloneArray(existing, resultSize);
            for (int i = 0; i < visible.length; i++) {
                result[i] = visible[i] == null ? null : visible[i].clone();
            }
            storage.put(owner, result);
            save();
        }

        @Override
        public synchronized ItemStack[] contents(UUID player) {
            ItemStack[] source = storage.getOrDefault(player, new ItemStack[0]);
            return cloneArray(source, source.length);
        }

        @Override
        public synchronized void setContents(UUID player, ItemStack[] contents) {
            if (contents == null) {
                storage.put(player, new ItemStack[18]);
            } else {
                int size = Math.max(18, Math.min(54, ((contents.length + 8) / 9) * 9));
                storage.put(player, cloneArray(contents, size));
            }
            save();
        }

        private ItemStack[] cloneArray(ItemStack[] source, int size) {
            ItemStack[] copy = new ItemStack[Math.max(0, size)];
            for (int i = 0; i < Math.min(source.length, copy.length); i++) {
                copy[i] = source[i] == null ? null : source[i].clone();
            }
            return copy;
        }

        void load() {
            plugin.getDataFolder().mkdirs();
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            var section = yaml.getConfigurationSection("players");
            if (section == null) return;
            for (String uuidText : section.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(uuidText);
                    List<?> list = yaml.getList("players." + uuidText + ".items", List.of());
                    int configured = yaml.getInt("players." + uuidText + ".size", Math.max(18, list.size()));
                    int size = Math.max(18, Math.max(configured, list.size()));
                    ItemStack[] items = new ItemStack[size];
                    for (int i = 0; i < Math.min(list.size(), size); i++) {
                        if (list.get(i) instanceof ItemStack stack) items[i] = stack.clone();
                    }
                    storage.put(id, items);
                } catch (IllegalArgumentException ignored) { }
            }
        }

        synchronized void save() {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, ItemStack[]> entry : storage.entrySet()) {
                yaml.set("players." + entry.getKey() + ".size", entry.getValue().length);
                yaml.set("players." + entry.getKey() + ".items", Arrays.asList(cloneArray(entry.getValue(), entry.getValue().length)));
            }
            try { yaml.save(file); }
            catch (IOException ex) { plugin.getLogger().severe("Could not save backpacks.yml: " + ex.getMessage()); }
        }
    }
}
