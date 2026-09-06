package gg.mira.backpacks;

import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import com.mira.core.api.ModuleHealth;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class MiraBackpacksPlugin extends JavaPlugin implements Listener, TabExecutor {
    private MiraCore core;
    private BackpackService service;
    private NamespacedKey idKey;
    private NamespacedKey levelKey;
    private final Map<UUID, UUID> activeEditors = new HashMap<>();

    @Override
    public void onEnable() {
        core = MiraCoreProvider.require();
        idKey = new NamespacedKey(this, "backpack_id");
        levelKey = new NamespacedKey(this, "backpack_level");
        service = new BackpackService(this);

        getServer().getServicesManager().register(BackpacksApi.class, service, this, ServicePriority.Normal);
        core.services().register(BackpacksApi.class, service);
        core.modules().register(this, "MiraBackpacks");
        core.modules().setHealth(this, ModuleHealth.HEALTHY,
                "Chestplate-linked backpack UUID storage ready");

        getServer().getPluginManager().registerEvents(this, this);
        var command = getCommand("backpack");
        if (command == null) throw new IllegalStateException("backpack command missing from plugin.yml");
        command.setExecutor(this);
        command.setTabCompleter(this);

        getServer().getScheduler().runTaskTimer(this, this::enforceWornSessions, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, this::auditLoadedDuplicates, 100L, 100L);
        getLogger().info("MiraBackpacks v" + getPluginMeta().getVersion() + " enabled with " + service.recordCount() + " stored backpack(s).");
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
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                msg(sender, "&cPlayers only.");
                return true;
            }
            openWorn(player);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "inspect" -> inspect(sender, args, false);
            case "edit" -> inspect(sender, args, true);
            case "status" -> status(sender, args);
            case "recover" -> recover(sender, args);
            default -> {
                msg(sender, "&e/backpack &7| &e/backpack <inspect|edit|status> <uuid> &7| &e/backpack recover <uuid> <player>");
                yield true;
            }
        };
    }

    private void openWorn(Player player) {
        if (!player.hasPermission("mirabackpacks.use")) {
            msg(player, "&cNo permission.");
            return;
        }

        ItemStack chestplate = player.getInventory().getChestplate();
        BackpackIdentity identity = service.identify(chestplate).orElse(null);
        if (identity == null) {
            msg(player, "&cWear a chestplate with the Backpack enchant.");
            return;
        }

        if (loadedInstances(identity.id()).size() > 1) {
            audit(player, "BACKPACK_DUPLICATE_ACCESS_BLOCKED", identity.id().toString(), Map.of());
            msg(player, "&cDuplicate backpack ID detected. Access blocked.");
            return;
        }

        if (!lock(identity.id(), player)) return;
        service.ensureRecord(identity.id(), identity.level());
        player.openInventory(service.inventory(identity.id(), AccessMode.EDITABLE, true));
    }

    private boolean inspect(CommandSender sender, String[] args, boolean editable) {
        if (!(sender instanceof Player player)) {
            msg(sender, "&cPlayers only.");
            return true;
        }
        String permission = editable ? "mirabackpacks.admin.edit" : "mirabackpacks.admin.inspect";
        if (!player.hasPermission(permission)) {
            msg(player, "&cNo permission.");
            return true;
        }
        UUID id = parseId(args, 1);
        if (id == null) {
            msg(player, "&eUsage: /backpack " + (editable ? "edit" : "inspect") + " <uuid>");
            return true;
        }

        BackpackRecord record = service.record(id).orElse(null);
        if (record == null) {
            msg(player, "&cUnknown backpack UUID.");
            return true;
        }

        if (editable) {
            if (!lock(id, player)) return true;
            player.openInventory(service.inventory(id, AccessMode.EDITABLE, false));
            audit(player, "BACKPACK_ADMIN_EDIT", id.toString(), Map.of("level", Integer.toString(record.level())));
        } else {
            player.openInventory(service.inventory(id, AccessMode.READ_ONLY, false));
            audit(player, "BACKPACK_ADMIN_INSPECT", id.toString(), Map.of("level", Integer.toString(record.level())));
        }
        return true;
    }

    private boolean status(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mirabackpacks.admin.inspect")) {
            msg(sender, "&cNo permission.");
            return true;
        }
        UUID id = parseId(args, 1);
        if (id == null) {
            msg(sender, "&eUsage: /backpack status <uuid>");
            return true;
        }

        BackpackRecord record = service.record(id).orElse(null);
        if (record == null) {
            msg(sender, "&cUnknown backpack UUID.");
            return true;
        }

        msg(sender, "&dBackpack " + roman(record.level()) + " &7- &f" + id);
        msg(sender, "&7Visible slots: &f" + slotsFor(record.level()) + " &7Stored slots: &f" + record.contents().length
                + " &7Used: &f" + service.usedSlots(id) + " &7Loaded linked items: &f" + loadedInstances(id).size());
        return true;
    }

    private boolean recover(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mirabackpacks.admin.recover")) {
            msg(sender, "&cNo permission.");
            return true;
        }
        UUID id = parseId(args, 1);
        Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : null;
        if (id == null || target == null) {
            msg(sender, "&eUsage: /backpack recover <uuid> <player>");
            return true;
        }

        BackpackRecord record = service.record(id).orElse(null);
        if (record == null) {
            msg(sender, "&cUnknown backpack UUID.");
            return true;
        }
        if (!loadedInstances(id).isEmpty()) {
            msg(sender, "&cRecovery blocked. A linked chestplate is already loaded.");
            return true;
        }

        ItemStack chestplate = target.getInventory().getChestplate();
        if (!isChestplate(chestplate)) {
            msg(sender, "&cTarget must be wearing a chestplate to recover onto.");
            return true;
        }
        if (service.identify(chestplate).isPresent()) {
            msg(sender, "&cThat chestplate is already linked to a backpack.");
            return true;
        }

        service.linkExisting(chestplate, id, record.level());
        target.getInventory().setChestplate(chestplate);
        audit(sender, "BACKPACK_RECOVERED", id.toString(), Map.of("target", target.getUniqueId().toString()));
        msg(sender, "&aRecovered backpack UUID onto &f" + target.getName() + "&a's worn chestplate.");
        return true;
    }

    private boolean lock(UUID id, Player viewer) {
        UUID existing = activeEditors.get(id);
        if (existing != null && !existing.equals(viewer.getUniqueId())) {
            msg(viewer, "&cThat backpack is already open.");
            return false;
        }
        activeEditors.put(id, viewer.getUniqueId());
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BackpackHolder holder)) return;
        if (holder.mode() == AccessMode.READ_ONLY) {
            event.setCancelled(true);
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        boolean intoTop = event.getRawSlot() >= 0 && event.getRawSlot() < topSize;
        ItemStack cursor = event.getCursor();
        ItemStack hotbar = event.getHotbarButton() >= 0 ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton()) : null;

        if (intoTop && (service.identify(cursor).isPresent() || service.identify(hotbar).isPresent())) {
            event.setCancelled(true);
            msg(event.getWhoClicked(), "&cBackpack-linked chestplates cannot be stored inside backpacks.");
            return;
        }
        if (event.isShiftClick() && event.getRawSlot() >= topSize && service.identify(event.getCurrentItem()).isPresent()) {
            event.setCancelled(true);
            msg(event.getWhoClicked(), "&cBackpack-linked chestplates cannot be stored inside backpacks.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BackpackHolder holder)) return;
        if (holder.mode() == AccessMode.READ_ONLY) {
            event.setCancelled(true);
            return;
        }
        if (service.identify(event.getOldCursor()).isEmpty()) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
            msg(event.getWhoClicked(), "&cBackpack-linked chestplates cannot be stored inside backpacks.");
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackpackHolder holder)) return;
        if (holder.mode() == AccessMode.READ_ONLY) return;
        service.store(holder.id(), event.getInventory().getContents());
        activeEditors.remove(holder.id(), event.getPlayer().getUniqueId());
    }

    private void enforceWornSessions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof BackpackHolder holder)) continue;
            if (!holder.requireWorn()) continue;

            BackpackIdentity current = service.identify(player.getInventory().getChestplate()).orElse(null);
            if (current != null && current.id().equals(holder.id())) continue;

            player.closeInventory();
            msg(player, "&cKeep that Backpack chestplate equipped to use it.");
        }
    }

    private void auditLoadedDuplicates() {
        Map<UUID, Integer> counts = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (ItemStack item : player.getInventory().getContents()) {
                BackpackIdentity identity = service.identify(item).orElse(null);
                if (identity != null) counts.merge(identity.id(), 1, Integer::sum);
            }
        }
        for (Map.Entry<UUID, Integer> entry : counts.entrySet()) {
            if (entry.getValue() <= 1) continue;
            core.audit().record("MiraBackpacks", "DUPLICATE_BACKPACK_ID", null, "SYSTEM", entry.getKey().toString(),
                    "Duplicate linked chestplate UUID detected", Map.of("count", Integer.toString(entry.getValue())));
        }
    }

    private List<String> loadedInstances(UUID id) {
        List<String> found = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack[] contents = player.getInventory().getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                BackpackIdentity identity = service.identify(contents[slot]).orElse(null);
                if (identity != null && identity.id().equals(id)) found.add(player.getName() + ":" + slot);
            }
        }
        return found;
    }

    private static boolean isChestplate(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        String name = item.getType().name();
        return name.endsWith("_CHESTPLATE") || item.getType() == Material.ELYTRA;
    }

    private UUID parseId(String[] args, int index) {
        if (args.length <= index) return null;
        try { return UUID.fromString(args[index]); }
        catch (IllegalArgumentException ex) { return null; }
    }

    private void audit(CommandSender actor, String action, String target, Map<String, String> metadata) {
        UUID actorId = actor instanceof Player p ? p.getUniqueId() : null;
        core.audit().record("MiraBackpacks", action, actorId, actor.getName(), target, action, metadata);
    }

    private void msg(CommandSender sender, String raw) { core.messages().send(sender, raw); }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            if (sender.hasPermission("mirabackpacks.admin.inspect")) out.addAll(List.of("inspect", "status"));
            if (sender.hasPermission("mirabackpacks.admin.edit")) out.add("edit");
            if (sender.hasPermission("mirabackpacks.admin.recover")) out.add("recover");
            return complete(args[0], out);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("recover")) {
            return complete(args[2], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        return List.of();
    }

    private static List<String> complete(String prefix, Collection<String> values) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower)).distinct().sorted().toList();
    }

    public enum AccessMode { EDITABLE, READ_ONLY }

    public record BackpackIdentity(UUID id, int level) { }

    public record BackpackRecord(UUID id, int level, ItemStack[] contents) { }

    public record BackpackHolder(UUID id, int level, AccessMode mode, boolean requireWorn) implements InventoryHolder {
        @Override public Inventory getInventory() { return Bukkit.createInventory(this, slotsFor(level), "Backpack"); }
    }

    public interface BackpacksApi {
        BackpackIdentity ensureLinked(ItemStack chestplate, int level);
        Optional<BackpackIdentity> identify(ItemStack item);
        int usedSlots(UUID backpackId);
        ItemStack[] contents(UUID backpackId);
        void setContents(UUID backpackId, ItemStack[] contents);
    }

    public final class BackpackService implements BackpacksApi {
        private final File file;
        private final Map<UUID, BackpackRecord> records = new LinkedHashMap<>();

        BackpackService(JavaPlugin plugin) {
            this.file = new File(plugin.getDataFolder(), "backpacks.yml");
            load();
        }

        int recordCount() { return records.size(); }

        @Override
        public BackpackIdentity ensureLinked(ItemStack chestplate, int level) {
            if (!isChestplate(chestplate)) throw new IllegalArgumentException("Backpack can only link chestplates");
            int safeLevel = Math.max(1, Math.min(6, level));
            BackpackIdentity existing = identify(chestplate).orElse(null);
            UUID id = existing == null ? UUID.randomUUID() : existing.id();

            linkExisting(chestplate, id, safeLevel);
            ensureRecord(id, safeLevel);
            save();
            return new BackpackIdentity(id, safeLevel);
        }

        void linkExisting(ItemStack chestplate, UUID id, int level) {
            ItemMeta meta = chestplate.getItemMeta();
            meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id.toString());
            meta.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, Math.max(1, Math.min(6, level)));
            chestplate.setItemMeta(meta);
        }

        @Override
        public Optional<BackpackIdentity> identify(ItemStack item) {
            if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
            String idText = item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
            Integer level = item.getItemMeta().getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
            if (idText == null || level == null || level < 1 || level > 6) return Optional.empty();
            try { return Optional.of(new BackpackIdentity(UUID.fromString(idText), level)); }
            catch (IllegalArgumentException ex) { return Optional.empty(); }
        }

        Optional<BackpackRecord> record(UUID id) { return Optional.ofNullable(records.get(id)); }

        void ensureRecord(UUID id, int level) {
            int safeLevel = Math.max(1, Math.min(6, level));
            int visible = slotsFor(safeLevel);
            records.compute(id, (ignored, current) -> {
                if (current == null) return new BackpackRecord(id, safeLevel, new ItemStack[visible]);

                int historicalSize = Math.max(current.contents().length, visible);
                ItemStack[] contents = cloneArray(current.contents(), historicalSize);
                return new BackpackRecord(id, safeLevel, contents);
            });
        }

        Inventory inventory(UUID id, AccessMode mode, boolean requireWorn) {
            BackpackRecord record = records.get(id);
            if (record == null) throw new IllegalArgumentException("Unknown backpack " + id);
            int visible = slotsFor(record.level());
            BackpackHolder holder = new BackpackHolder(id, record.level(), mode, requireWorn);
            Inventory inventory = Bukkit.createInventory(holder, visible,
                    "§8Backpack " + roman(record.level()) + (mode == AccessMode.READ_ONLY ? " §7[View]" : ""));

            for (int i = 0; i < Math.min(visible, record.contents().length); i++) {
                ItemStack item = record.contents()[i];
                if (item != null) inventory.setItem(i, item.clone());
            }
            return inventory;
        }

        synchronized void store(UUID id, ItemStack[] visible) {
            BackpackRecord current = records.get(id);
            if (current == null) return;

            int size = Math.max(current.contents().length, visible.length);
            ItemStack[] contents = cloneArray(current.contents(), size);
            for (int i = 0; i < visible.length; i++) {
                ItemStack item = visible[i];
                contents[i] = identify(item).isPresent() ? null : (item == null ? null : item.clone());
            }
            records.put(id, new BackpackRecord(id, current.level(), contents));
            save();
        }

        @Override
        public int usedSlots(UUID backpackId) {
            BackpackRecord record = records.get(backpackId);
            if (record == null) return 0;
            int used = 0;
            for (ItemStack item : record.contents()) if (item != null && !item.getType().isAir()) used++;
            return used;
        }

        @Override
        public ItemStack[] contents(UUID backpackId) {
            BackpackRecord record = records.get(backpackId);
            return record == null ? new ItemStack[0] : cloneArray(record.contents(), record.contents().length);
        }

        @Override
        public synchronized void setContents(UUID backpackId, ItemStack[] contents) {
            BackpackRecord current = records.get(backpackId);
            if (current == null) throw new IllegalArgumentException("Unknown backpack");
            int size = Math.max(current.contents().length, contents == null ? 0 : contents.length);
            ItemStack[] clean = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                ItemStack item = contents != null && i < contents.length ? contents[i] : null;
                if (identify(item).isPresent()) throw new IllegalArgumentException("Backpack nesting is not allowed");
                clean[i] = item == null ? null : item.clone();
            }
            records.put(backpackId, new BackpackRecord(backpackId, current.level(), clean));
            save();
        }

        void load() {
            getDataFolder().mkdirs();
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection root = yaml.getConfigurationSection("backpacks");
            if (root == null) return;
            for (String key : root.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    int level = Math.max(1, Math.min(6, yaml.getInt("backpacks." + key + ".level", 1)));
                    List<?> raw = yaml.getList("backpacks." + key + ".items", List.of());
                    int size = Math.max(slotsFor(level), raw.size());
                    ItemStack[] contents = new ItemStack[size];
                    for (int i = 0; i < raw.size(); i++) {
                        if (raw.get(i) instanceof ItemStack stack && identify(stack).isEmpty()) contents[i] = stack.clone();
                    }
                    records.put(id, new BackpackRecord(id, level, contents));
                } catch (IllegalArgumentException ignored) { }
            }
        }

        synchronized void save() {
            YamlConfiguration yaml = new YamlConfiguration();
            for (BackpackRecord record : records.values()) {
                String path = "backpacks." + record.id();
                yaml.set(path + ".level", record.level());
                yaml.set(path + ".items", Arrays.asList(cloneArray(record.contents(), record.contents().length)));
            }
            try { yaml.save(file); }
            catch (IOException ex) { getLogger().severe("Could not save backpacks.yml: " + ex.getMessage()); }
        }

        private ItemStack[] cloneArray(ItemStack[] source, int size) {
            ItemStack[] copy = new ItemStack[size];
            for (int i = 0; i < Math.min(source.length, size); i++) copy[i] = source[i] == null ? null : source[i].clone();
            return copy;
        }
    }

    public static int slotsFor(int level) {
        return Math.max(1, Math.min(6, level)) * 9;
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III";
            case 4 -> "IV"; case 5 -> "V"; case 6 -> "VI";
            default -> Integer.toString(level);
        };
    }
}
