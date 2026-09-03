package gg.mira.backpacks;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class MiraBackpacksPlugin extends JavaPlugin implements Listener {
    private BackpackService service;

    @Override public void onEnable() {
        service = new BackpackService(this);
        getServer().getServicesManager().register(BackpacksApi.class, service, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override public void onDisable() { service.save(); getServer().getServicesManager().unregisterAll(this); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("§cPlayers only."); return true; }
        if (args.length >= 2 && args[0].equalsIgnoreCase("inspect")) {
            if (!player.hasPermission("mirabackpacks.inspect")) { player.sendMessage("§cNo permission."); return true; }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            player.openInventory(service.createInventory(target.getUniqueId(), target.getName() == null ? args[1] : target.getName(), service.sizeFor(target.getPlayer())));
            return true;
        }
        player.openInventory(service.createInventory(player.getUniqueId(), player.getName(), service.sizeFor(player)));
        return true;
    }

    @EventHandler public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof BackpackHolder holder) service.store(holder.owner(), event.getInventory().getContents());
    }

    public interface BackpacksApi {
        int configuredSize(UUID player);
        ItemStack[] contents(UUID player);
        void setContents(UUID player, ItemStack[] contents);
    }

    public record BackpackHolder(UUID owner, String ownerName) implements InventoryHolder {
        @Override public Inventory getInventory() { return Bukkit.createInventory(this, 9, "Backpack"); }
    }

    public static final class BackpackService implements BackpacksApi {
        private final MiraBackpacksPlugin plugin;
        private final File file;
        private final Map<UUID, ItemStack[]> storage = new HashMap<>();

        BackpackService(MiraBackpacksPlugin plugin) { this.plugin = plugin; this.file = new File(plugin.getDataFolder(), "backpacks.yml"); load(); }

        int sizeFor(Player player) {
            if (player == null) return 18;
            for (int size : new int[]{54,45,36,27,18}) if (player.hasPermission("mirabackpacks.size." + size)) return size;
            return 18;
        }

        Inventory createInventory(UUID owner, String name, int size) {
            size = Math.max(9, Math.min(54, ((size + 8) / 9) * 9));
            BackpackHolder holder = new BackpackHolder(owner, name);
            Inventory inv = Bukkit.createInventory(holder, size, "§8" + name + "'s Backpack");
            ItemStack[] existing = storage.getOrDefault(owner, new ItemStack[0]);
            for (int i = 0; i < Math.min(existing.length, size); i++) if (existing[i] != null) inv.setItem(i, existing[i].clone());
            return inv;
        }

        void store(UUID owner, ItemStack[] items) {
            ItemStack[] copy = new ItemStack[items.length];
            for (int i = 0; i < items.length; i++) copy[i] = items[i] == null ? null : items[i].clone();
            storage.put(owner, copy); save();
        }

        @Override public int configuredSize(UUID player) { return storage.getOrDefault(player, new ItemStack[18]).length; }
        @Override public ItemStack[] contents(UUID player) { ItemStack[] src = storage.getOrDefault(player, new ItemStack[0]); ItemStack[] copy = new ItemStack[src.length]; for (int i=0;i<src.length;i++) copy[i]=src[i]==null?null:src[i].clone(); return copy; }
        @Override public void setContents(UUID player, ItemStack[] contents) { store(player, contents); }

        void load() {
            plugin.getDataFolder().mkdirs();
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            var section = y.getConfigurationSection("players"); if (section == null) return;
            for (String uuidText : section.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(uuidText);
                    List<?> list = y.getList("players." + uuidText + ".items", List.of());
                    int size = y.getInt("players." + uuidText + ".size", Math.max(18, list.size()));
                    ItemStack[] items = new ItemStack[size];
                    for (int i=0;i<Math.min(list.size(), size);i++) if (list.get(i) instanceof ItemStack stack) items[i] = stack;
                    storage.put(id, items);
                } catch (IllegalArgumentException ignored) {}
            }
        }

        synchronized void save() {
            YamlConfiguration y = new YamlConfiguration();
            for (var e : storage.entrySet()) {
                y.set("players." + e.getKey() + ".size", e.getValue().length);
                y.set("players." + e.getKey() + ".items", Arrays.asList(e.getValue()));
            }
            try { y.save(file); } catch (IOException ex) { plugin.getLogger().severe("Could not save backpacks.yml: " + ex.getMessage()); }
        }
    }
}
