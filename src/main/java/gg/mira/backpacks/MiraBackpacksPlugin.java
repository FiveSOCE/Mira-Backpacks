package gg.mira.backpacks;

import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import com.mira.core.api.ModuleHealth;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
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
    private NamespacedKey backpackKey;
    private NamespacedKey idKey;
    private NamespacedKey tierKey;
    private final Map<UUID, UUID> activeEditors = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        core = MiraCoreProvider.require();
        backpackKey = new NamespacedKey(this, "backpack");
        idKey = new NamespacedKey(this, "backpack_id");
        tierKey = new NamespacedKey(this, "backpack_tier");
        service = new BackpackService(this);

        getServer().getServicesManager().register(BackpacksApi.class, service, this, ServicePriority.Normal);
        core.services().register(BackpacksApi.class, service);
        core.modules().register(this, "MiraBackpacks");
        core.modules().setHealth(this, ModuleHealth.HEALTHY,
                "Unique physical backpack items, off-hand access and UUID storage ready");

        getServer().getPluginManager().registerEvents(this, this);
        var command = getCommand("backpack");
        if (command == null) throw new IllegalStateException("backpack command missing from plugin.yml");
        command.setExecutor(this);
        command.setTabCompleter(this);

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
            openHeld(player);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "give" -> give(sender, args);
            case "inspect" -> inspect(sender, args, false);
            case "edit" -> inspect(sender, args, true);
            case "status" -> status(sender, args);
            case "recover" -> recover(sender, args);
            case "reload" -> reload(sender);
            default -> {
                msg(sender, "&e/backpack &7| &e/backpack give <player> <tier> [amount] &7| &e/backpack <inspect|edit|status> <uuid> &7| &e/backpack recover <uuid> <player>");
                yield true;
            }
        };
    }

    private boolean give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mirabackpacks.admin")) {
            msg(sender, "&cNo permission.");
            return true;
        }
        if (args.length < 3) {
            msg(sender, "&eUsage: /backpack give <player> <small|medium|large|elite|mythic|godly> [amount]");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        BackpackTier tier = BackpackTier.from(args[2]).orElse(null);
        if (target == null || tier == null) {
            msg(sender, "&cPlayer/tier not found.");
            return true;
        }
        int amount = 1;
        if (args.length >= 4) {
            try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[3]))); }
            catch (NumberFormatException ex) {
                msg(sender, "&cAmount must be 1-64.");
                return true;
            }
        }

        for (int i = 0; i < amount; i++) {
            ItemStack backpack = service.create(tier);
            Map<Integer, ItemStack> overflow = target.getInventory().addItem(backpack);
            overflow.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));
        }
        audit(sender, "BACKPACK_GIVEN", target.getUniqueId().toString(), Map.of("tier", tier.id(), "amount", Integer.toString(amount)));
        msg(sender, "&aGave &f" + target.getName() + " &a" + amount + "x &f" + tier.displayName() + "&a.");
        return true;
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
            msg(player, "&eUsage: /backpack " + (editable ? "edit" : "inspect") + " <backpack-uuid>");
            return true;
        }
        BackpackRecord record = service.record(id).orElse(null);
        if (record == null) {
            msg(player, "&cUnknown backpack UUID.");
            return true;
        }

        if (editable) {
            if (!lock(id, player)) return true;
            player.openInventory(service.inventory(id, AccessMode.EDITABLE));
            audit(player, "BACKPACK_ADMIN_EDIT", id.toString(), Map.of("tier", record.tier().id()));
        } else {
            player.openInventory(service.inventory(id, AccessMode.READ_ONLY));
            audit(player, "BACKPACK_ADMIN_INSPECT", id.toString(), Map.of("tier", record.tier().id()));
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
            msg(sender, "&eUsage: /backpack status <backpack-uuid>");
            return true;
        }
        BackpackRecord record = service.record(id).orElse(null);
        if (record == null) {
            msg(sender, "&cUnknown backpack UUID.");
            return true;
        }
        int loaded = loadedInstances(id).size();
        msg(sender, "&d" + record.tier().displayName() + " &7- &f" + id);
        msg(sender, "&7Slots: &f" + record.tier().slots() + " &7Used: &f" + service.usedSlots(id)
                + " &7Loaded physical copies: &f" + loaded);
        UUID editor = activeEditors.get(id);
        msg(sender, "&7Active editor: &f" + (editor == null ? "None" : Optional.ofNullable(Bukkit.getPlayer(editor)).map(Player::getName).orElse(editor.toString())));
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
            msg(sender, "&eUsage: /backpack recover <backpack-uuid> <player>");
            return true;
        }
        BackpackRecord record = service.record(id).orElse(null);
        if (record == null) {
            msg(sender, "&cUnknown backpack UUID.");
            return true;
        }
        List<String> found = loadedInstances(id);
        if (!found.isEmpty()) {
            msg(sender, "&cRecovery blocked. A physical copy is already loaded: &f" + String.join(", ", found));
            return true;
        }

        ItemStack item = service.createExisting(id, record.tier());
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(item);
        overflow.values().forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        audit(sender, "BACKPACK_RECOVERED", id.toString(), Map.of("target", target.getUniqueId().toString(), "tier", record.tier().id()));
        msg(sender, "&aRecovered backpack &f" + id + " &ato &f" + target.getName() + "&a.");
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("mirabackpacks.admin")) {
            msg(sender, "&cNo permission.");
            return true;
        }
        reloadConfig();
        msg(sender, "&aMiraBackpacks config reloaded.");
        return true;
    }

    private void openHeld(Player player) {
        if (!player.hasPermission("mirabackpacks.use")) {
            msg(player, "&cNo permission.");
            return;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        BackpackIdentity identity = service.identify(offhand).orElse(null);
        if (identity == null) {
            msg(player, "&cHold a Mira backpack in your off hand.");
            return;
        }
        if (loadedInstances(identity.id()).size() > 1) {
            audit(player, "BACKPACK_DUPLICATE_ACCESS_BLOCKED", identity.id().toString(), Map.of());
            msg(player, "&cDuplicate backpack ID detected. Access blocked. Contact staff.");
            return;
        }
        if (!lock(identity.id(), player)) return;
        service.ensureRecord(identity.id(), identity.tier());
        player.openInventory(service.inventory(identity.id(), AccessMode.EDITABLE));
    }

    private boolean lock(UUID id, Player viewer) {
        UUID existing = activeEditors.get(id);
        if (existing != null && !existing.equals(viewer.getUniqueId())) {
            String name = Optional.ofNullable(Bukkit.getPlayer(existing)).map(Player::getName).orElse(existing.toString());
            msg(viewer, "&cThat backpack is already open by &f" + name + "&c.");
            return false;
        }
        activeEditors.put(id, viewer.getUniqueId());
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.OFF_HAND) return;
        if (!event.getAction().isRightClick()) return;
        if (service.identify(event.getItem()).isEmpty()) return;
        event.setCancelled(true);
        openHeld(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BackpackHolder holder)) return;

        if (holder.mode() == AccessMode.READ_ONLY) {
            event.setCancelled(true);
            return;
        }

        // A backpack can never be placed inside any Mira backpack.
        int raw = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        ItemStack cursor = event.getCursor();
        ItemStack hotbar = event.getHotbarButton() >= 0 ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton()) : null;

        boolean intoTop = raw >= 0 && raw < topSize;
        if (intoTop && (service.isBackpack(cursor) || service.isBackpack(hotbar))) {
            event.setCancelled(true);
            msg(event.getWhoClicked(), "&cBackpacks cannot be stored inside backpacks.");
            return;
        }

        // Shift-click from player inventory into backpack.
        if (event.isShiftClick() && raw >= topSize && service.isBackpack(event.getCurrentItem())) {
            event.setCancelled(true);
            msg(event.getWhoClicked(), "&cBackpacks cannot be stored inside backpacks.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BackpackHolder holder)) return;
        if (holder.mode() == AccessMode.READ_ONLY) {
            event.setCancelled(true);
            return;
        }
        if (!service.isBackpack(event.getOldCursor())) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
            msg(event.getWhoClicked(), "&cBackpacks cannot be stored inside backpacks.");
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackpackHolder holder)) return;
        if (holder.mode() == AccessMode.READ_ONLY) return;

        if (containsBackpack(event.getInventory().getContents())) {
            // Defensive cleanup in case another plugin bypassed click/drag protections.
            for (ItemStack item : event.getInventory().getContents()) {
                if (service.isBackpack(item)) {
                    event.getPlayer().getInventory().addItem(item).values()
                            .forEach(left -> event.getPlayer().getWorld().dropItemNaturally(event.getPlayer().getLocation(), left));
                }
            }
        }
        service.store(holder.id(), event.getInventory().getContents());
        activeEditors.remove(holder.id(), event.getPlayer().getUniqueId());
    }

    private boolean containsBackpack(ItemStack[] contents) {
        for (ItemStack item : contents) if (service.isBackpack(item)) return true;
        return false;
    }

    private void auditLoadedDuplicates() {
        Map<UUID, List<String>> seen = new HashMap<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack[] contents = player.getInventory().getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                BackpackIdentity id = service.identify(contents[slot]).orElse(null);
                if (id == null) continue;
                seen.computeIfAbsent(id.id(), ignored -> new ArrayList<>()).add("player " + player.getName() + " slot " + slot);
            }
        }
        for (var world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                BackpackIdentity id = service.identify(item.getItemStack()).orElse(null);
                if (id == null) continue;
                seen.computeIfAbsent(id.id(), ignored -> new ArrayList<>()).add("ground " + world.getName() + " "
                        + item.getLocation().getBlockX() + "," + item.getLocation().getBlockY() + "," + item.getLocation().getBlockZ());
            }
        }

        for (Map.Entry<UUID, List<String>> entry : seen.entrySet()) {
            if (entry.getValue().size() <= 1) continue;
            core.audit().record("MiraBackpacks", "DUPLICATE_BACKPACK_ID", null, "SYSTEM", entry.getKey().toString(),
                    "Duplicate physical backpack UUID detected", Map.of("locations", String.join(" | ", entry.getValue())));
        }
    }

    private List<String> loadedInstances(UUID id) {
        List<String> found = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack[] contents = player.getInventory().getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                BackpackIdentity foundId = service.identify(contents[slot]).orElse(null);
                if (foundId != null && foundId.id().equals(id)) found.add("player " + player.getName() + " slot " + slot);
            }
        }
        for (var world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                BackpackIdentity foundId = service.identify(item.getItemStack()).orElse(null);
                if (foundId != null && foundId.id().equals(id)) found.add("ground " + world.getName());
            }
        }
        return found;
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
            List<String> values = new ArrayList<>();
            if (sender.hasPermission("mirabackpacks.admin")) values.add("give");
            if (sender.hasPermission("mirabackpacks.admin.inspect")) values.addAll(List.of("inspect", "status"));
            if (sender.hasPermission("mirabackpacks.admin.edit")) values.add("edit");
            if (sender.hasPermission("mirabackpacks.admin.recover")) values.add("recover");
            if (sender.hasPermission("mirabackpacks.admin")) values.add("reload");
            return complete(args[0], values);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return complete(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return complete(args[2], Arrays.stream(BackpackTier.values()).map(BackpackTier::id).toList());
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

    public enum BackpackTier {
        SMALL("small", "&7Small Backpack", 9, 20001),
        MEDIUM("medium", "&aMedium Backpack", 18, 20002),
        LARGE("large", "&bLarge Backpack", 27, 20003),
        ELITE("elite", "&5Elite Backpack", 36, 20004),
        MYTHIC("mythic", "&dMythic Backpack", 45, 20005),
        GODLY("godly", "&6Godly Backpack", 54, 20006);

        private final String id;
        private final String displayName;
        private final int slots;
        private final int modelData;

        BackpackTier(String id, String displayName, int slots, int modelData) {
            this.id = id;
            this.displayName = displayName;
            this.slots = slots;
            this.modelData = modelData;
        }

        public String id() { return id; }
        public String displayName() { return displayName; }
        public int slots() { return slots; }
        public int modelData() { return modelData; }

        static Optional<BackpackTier> from(String raw) {
            if (raw == null) return Optional.empty();
            return Arrays.stream(values()).filter(t -> t.id.equalsIgnoreCase(raw) || t.name().equalsIgnoreCase(raw)).findFirst();
        }
    }

    public record BackpackIdentity(UUID id, BackpackTier tier) { }

    public record BackpackRecord(UUID id, BackpackTier tier, ItemStack[] contents) { }

    public record BackpackHolder(UUID id, BackpackTier tier, AccessMode mode) implements InventoryHolder {
        @Override public Inventory getInventory() { return Bukkit.createInventory(this, tier.slots(), "Backpack"); }
    }

    public interface BackpacksApi {
        Optional<BackpackIdentity> identify(ItemStack item);
        ItemStack create(BackpackTier tier);
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
        public ItemStack create(BackpackTier tier) {
            UUID id = UUID.randomUUID();
            ensureRecord(id, tier);
            save();
            return createExisting(id, tier);
        }

        ItemStack createExisting(UUID id, BackpackTier tier) {
            Material material = materialFor(tier);
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(core.messages().parse(displayFor(tier)).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = List.of(
                    core.messages().parse("&7" + tier.slots() + " storage slots").decoration(TextDecoration.ITALIC, false),
                    core.messages().parse("&7Place in your off hand to use.").decoration(TextDecoration.ITALIC, false),
                    core.messages().parse("&8ID: " + id).decoration(TextDecoration.ITALIC, false)
            );
            meta.lore(lore);
            meta.setCustomModelData(modelDataFor(tier));
            meta.getPersistentDataContainer().set(backpackKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id.toString());
            meta.getPersistentDataContainer().set(tierKey, PersistentDataType.STRING, tier.id());
            item.setItemMeta(meta);
            return item;
        }

        @Override
        public Optional<BackpackIdentity> identify(ItemStack item) {
            if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
            ItemMeta meta = item.getItemMeta();
            Byte marker = meta.getPersistentDataContainer().get(backpackKey, PersistentDataType.BYTE);
            String idText = meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
            String tierText = meta.getPersistentDataContainer().get(tierKey, PersistentDataType.STRING);
            if (marker == null || marker != (byte) 1 || idText == null || tierText == null) return Optional.empty();
            try {
                UUID id = UUID.fromString(idText);
                BackpackTier tier = BackpackTier.from(tierText).orElse(null);
                return tier == null ? Optional.empty() : Optional.of(new BackpackIdentity(id, tier));
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }

        boolean isBackpack(ItemStack item) { return identify(item).isPresent(); }

        Optional<BackpackRecord> record(UUID id) { return Optional.ofNullable(records.get(id)); }

        void ensureRecord(UUID id, BackpackTier tier) {
            records.compute(id, (ignored, existing) -> {
                if (existing == null) return new BackpackRecord(id, tier, new ItemStack[tier.slots()]);
                if (existing.tier() == tier && existing.contents().length == tier.slots()) return existing;
                ItemStack[] resized = new ItemStack[tier.slots()];
                for (int i = 0; i < Math.min(existing.contents().length, resized.length); i++) {
                    resized[i] = existing.contents()[i] == null ? null : existing.contents()[i].clone();
                }
                return new BackpackRecord(id, tier, resized);
            });
        }

        Inventory inventory(UUID id, AccessMode mode) {
            BackpackRecord record = records.get(id);
            if (record == null) throw new IllegalArgumentException("Unknown backpack " + id);
            BackpackHolder holder = new BackpackHolder(id, record.tier(), mode);
            Inventory inventory = Bukkit.createInventory(holder, record.tier().slots(),
                    "§8" + stripColors(record.tier().displayName()) + (mode == AccessMode.READ_ONLY ? " §7[View]" : ""));
            for (int i = 0; i < record.contents().length; i++) {
                ItemStack item = record.contents()[i];
                if (item != null) inventory.setItem(i, item.clone());
            }
            return inventory;
        }

        synchronized void store(UUID id, ItemStack[] visible) {
            BackpackRecord existing = records.get(id);
            if (existing == null) return;
            ItemStack[] clean = new ItemStack[existing.tier().slots()];
            for (int i = 0; i < Math.min(clean.length, visible.length); i++) {
                ItemStack item = visible[i];
                clean[i] = isBackpack(item) ? null : (item == null ? null : item.clone());
            }
            records.put(id, new BackpackRecord(id, existing.tier(), clean));
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
            if (record == null) return new ItemStack[0];
            return cloneArray(record.contents());
        }

        @Override
        public synchronized void setContents(UUID backpackId, ItemStack[] contents) {
            BackpackRecord existing = records.get(backpackId);
            if (existing == null) throw new IllegalArgumentException("Unknown backpack");
            ItemStack[] clean = new ItemStack[existing.tier().slots()];
            for (int i = 0; i < Math.min(clean.length, contents == null ? 0 : contents.length); i++) {
                ItemStack item = contents[i];
                if (isBackpack(item)) throw new IllegalArgumentException("Backpack nesting is not allowed");
                clean[i] = item == null ? null : item.clone();
            }
            records.put(backpackId, new BackpackRecord(backpackId, existing.tier(), clean));
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
                    BackpackTier tier = BackpackTier.from(yaml.getString("backpacks." + key + ".tier", "")).orElse(null);
                    if (tier == null) continue;
                    List<?> raw = yaml.getList("backpacks." + key + ".items", List.of());
                    ItemStack[] contents = new ItemStack[tier.slots()];
                    for (int i = 0; i < Math.min(raw.size(), contents.length); i++) {
                        if (raw.get(i) instanceof ItemStack stack && !isBackpack(stack)) contents[i] = stack.clone();
                    }
                    records.put(id, new BackpackRecord(id, tier, contents));
                } catch (IllegalArgumentException ignored) { }
            }
        }

        synchronized void save() {
            YamlConfiguration yaml = new YamlConfiguration();
            for (BackpackRecord record : records.values()) {
                String path = "backpacks." + record.id();
                yaml.set(path + ".tier", record.tier().id());
                yaml.set(path + ".items", Arrays.asList(cloneArray(record.contents())));
            }
            try { yaml.save(file); }
            catch (IOException ex) { getLogger().severe("Could not save backpacks.yml: " + ex.getMessage()); }
        }

        private ItemStack[] cloneArray(ItemStack[] source) {
            ItemStack[] copy = new ItemStack[source.length];
            for (int i = 0; i < source.length; i++) copy[i] = source[i] == null ? null : source[i].clone();
            return copy;
        }

        private Material materialFor(BackpackTier tier) {
            return Material.matchMaterial(getConfig().getString("tiers." + tier.id() + ".material", "LEATHER")) == null
                    ? Material.LEATHER
                    : Material.matchMaterial(getConfig().getString("tiers." + tier.id() + ".material", "LEATHER"));
        }

        private int modelDataFor(BackpackTier tier) {
            return getConfig().getInt("tiers." + tier.id() + ".custom-model-data", tier.modelData());
        }

        private String displayFor(BackpackTier tier) {
            return getConfig().getString("tiers." + tier.id() + ".name", tier.displayName());
        }

        private String stripColors(String input) {
            return input == null ? "Backpack" : input.replaceAll("(?i)&[0-9A-FK-ORX]", "");
        }
    }
}
