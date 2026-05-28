package com.plahen.anarchy;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class PlahenAnarchyCore extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final long ALERT_COOLDOWN_MILLIS = 8_000L;

    private final Map<String, ChunkWindow> chunkWindows = new HashMap<>();
    private final Map<UUID, Integer> clickWindows = new HashMap<>();
    private final Map<UUID, Integer> placeWindows = new HashMap<>();
    private final Map<UUID, Integer> breakWindows = new HashMap<>();
    private final Map<UUID, Integer> airSeconds = new HashMap<>();
    private final Map<UUID, Integer> violationCounts = new HashMap<>();
    private final Map<UUID, Location> lastMoveLocations = new HashMap<>();
    private final Map<UUID, Long> lastMoveTimes = new HashMap<>();
    private final Map<String, Long> alertCooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        initializeSpawnCenter();
        Objects.requireNonNull(getCommand("plahen")).setExecutor(this);
        Objects.requireNonNull(getCommand("plahen")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::resetShortWindows, 20L, 20L);
        getLogger().info("PlahenAnarchyCore enabled: fair anarchy utilities are active.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            sendInfo(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("plahen.admin")) {
                sender.sendMessage(pluginMessage("&cНет прав."));
                return true;
            }
            reloadConfig();
            sender.sendMessage(pluginMessage("&aКонфиг PlahenAnarchyCore перезагружен."));
            return true;
        }

        if (args[0].equalsIgnoreCase("setspawn")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can set the spawn center.");
                return true;
            }
            if (!sender.hasPermission("plahen.admin")) {
                sender.sendMessage(pluginMessage("&cНет прав."));
                return true;
            }
            saveSpawnCenter(player.getLocation());
            sender.sendMessage(pluginMessage("&aЦентр защищённого спавна сохранён."));
            return true;
        }

        sender.sendMessage(pluginMessage("&eИспользование: /" + label + " <info|reload|setspawn>"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }
        List<String> options = new ArrayList<>(List.of("info"));
        if (sender.hasPermission("plahen.admin")) {
            options.add("reload");
            options.add("setspawn");
        }
        return options.stream().filter(value -> value.startsWith(args[0].toLowerCase())).toList();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> sendInfo(event.getPlayer()), 40L);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isProtectedSpawn(event.getBlock().getLocation()) && !getConfig().getBoolean("spawn.block-edit-enabled", false)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(pluginMessage("&cСпавн защищён. Выйди дальше от центра."));
            return;
        }

        if (getConfig().getBoolean("alerts.fast-break-check-enabled", true)) {
            Player player = event.getPlayer();
            int breaks = breakWindows.merge(player.getUniqueId(), 1, Integer::sum);
            int threshold = getConfig().getInt("alerts.fast-break-threshold-per-second", 18);
            if (breaks == threshold + 1) {
                alertOperators(player, "FastBreak/Nuker", "ломает блоки слишком быстро: " + breaks + "/сек");
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isProtectedSpawn(event.getBlock().getLocation()) && !getConfig().getBoolean("spawn.block-edit-enabled", false)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(pluginMessage("&cСпавн защищён. Выйди дальше от центра."));
            return;
        }
        if (event.getBlockPlaced().getType() == Material.HOPPER && countBlocks(event.getBlock().getChunk(), Material.HOPPER) >= getConfig().getInt("anti-lag.hopper-limit-per-chunk", 64)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(pluginMessage("&cВ этом чанке слишком много воронок."));
            return;
        }

        if (getConfig().getBoolean("alerts.fast-place-check-enabled", true)) {
            Player player = event.getPlayer();
            int places = placeWindows.merge(player.getUniqueId(), 1, Integer::sum);
            int threshold = getConfig().getInt("alerts.fast-place-threshold-per-second", 12);
            if (places == threshold + 1) {
                alertOperators(player, "Scaffold/FastPlace", "ставит блоки слишком быстро: " + places + "/сек");
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onRedstone(BlockRedstoneEvent event) {
        ChunkWindow window = window(event.getBlock().getChunk());
        window.redstoneEvents++;
        if (window.redstoneEvents > getConfig().getInt("anti-lag.redstone-events-per-chunk-per-second", 220)) {
            event.setNewCurrent(0);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        throttlePiston(event.getBlock(), event);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        throttlePiston(event.getBlock(), event);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntitySpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof TNTPrimed)) {
            return;
        }

        Chunk chunk = entity.getChunk();
        ChunkWindow window = window(chunk);
        window.tntEvents++;
        if (window.tntEvents > getConfig().getInt("anti-lag.tnt-prime-limit-per-chunk", 48) || countTnt(chunk) >= getConfig().getInt("anti-lag.tnt-prime-limit-per-chunk", 48)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker && getConfig().getBoolean("alerts.reach-check-enabled", true)) {
            double reach = attacker.getEyeLocation().distance(event.getEntity().getLocation());
            double threshold = getConfig().getDouble("alerts.reach-threshold", 3.35D);
            if (reach > threshold) {
                alertOperators(attacker, "Reach/KillAura", "удар с дистанции " + String.format("%.2f", reach) + " блоков");
            }
        }

        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!getConfig().getBoolean("spawn.pvp-enabled", false) && isProtectedSpawn(victim.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (isProtectedSpawn(event.getLocation()) && !getConfig().getBoolean("spawn.explosion-enabled", false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (isProtectedSpawn(event.getBlock().getLocation()) && !getConfig().getBoolean("spawn.explosion-enabled", false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING || !getConfig().getBoolean("alerts.cps-check-enabled", true)) {
            return;
        }
        Player player = event.getPlayer();
        int clicks = clickWindows.merge(player.getUniqueId(), 1, Integer::sum);
        int threshold = getConfig().getInt("alerts.cps-threshold", 18);
        if (clicks == threshold + 1) {
            alertOperators(player, "AutoClicker", "слишком высокий CPS: " + clicks + "/сек");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("alerts.speed-check-enabled", true) || event.getTo() == null) {
            return;
        }

        Player player = event.getPlayer();
        if (player.getAllowFlight() || player.isFlying() || player.isGliding() || player.isInsideVehicle() || player.isSwimming()) {
            rememberMove(player, event.getTo());
            return;
        }

        UUID playerId = player.getUniqueId();
        Location previous = lastMoveLocations.get(playerId);
        Long previousTime = lastMoveTimes.get(playerId);
        rememberMove(player, event.getTo());

        if (previous == null || previousTime == null || !previous.getWorld().equals(event.getTo().getWorld())) {
            return;
        }

        long elapsedMillis = Math.max(1L, System.currentTimeMillis() - previousTime);
        if (elapsedMillis > 1_500L) {
            return;
        }

        double horizontalDistance = Math.hypot(event.getTo().getX() - previous.getX(), event.getTo().getZ() - previous.getZ());
        double blocksPerSecond = horizontalDistance * 1000.0D / elapsedMillis;
        double threshold = getConfig().getDouble("alerts.speed-threshold-blocks-per-second", 9.0D);
        if (blocksPerSecond > threshold) {
            alertOperators(player, "Speed", "двигается слишком быстро: " + String.format("%.2f", blocksPerSecond) + " блок/сек");
        }
    }

    private void throttlePiston(Block block, org.bukkit.event.Cancellable event) {
        ChunkWindow window = window(block.getChunk());
        window.pistonEvents++;
        if (window.pistonEvents > getConfig().getInt("anti-lag.piston-events-per-chunk-per-second", 120)) {
            event.setCancelled(true);
        }
    }

    private void resetShortWindows() {
        chunkWindows.clear();
        clickWindows.clear();
        placeWindows.clear();
        breakWindows.clear();
        if (!getConfig().getBoolean("alerts.fly-check-enabled", true)) {
            airSeconds.clear();
            return;
        }
        int threshold = getConfig().getInt("alerts.fly-air-seconds", 7);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isSafelyAirborne(player)) {
                int seconds = airSeconds.merge(player.getUniqueId(), 1, Integer::sum);
                if (seconds == threshold) {
                    alertOperators(player, "Fly", "находится в воздухе без причины " + seconds + " сек");
                }
            } else {
                airSeconds.remove(player.getUniqueId());
            }
        }
    }

    private boolean isSafelyAirborne(Player player) {
        if (player.isOnGround() || player.isInsideVehicle() || player.isGliding() || player.isSwimming() || player.getAllowFlight()) {
            return false;
        }
        Material feet = player.getLocation().getBlock().getType();
        Material below = player.getLocation().subtract(0, 1, 0).getBlock().getType();
        return !feet.isLiquid() && !below.isLiquid() && below.isAir();
    }

    private void rememberMove(Player player, Location location) {
        lastMoveLocations.put(player.getUniqueId(), location.clone());
        lastMoveTimes.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private ChunkWindow window(Chunk chunk) {
        return chunkWindows.computeIfAbsent(chunkKey(chunk), ignored -> new ChunkWindow());
    }

    private String chunkKey(Chunk chunk) {
        return chunk.getWorld().getUID() + ":" + chunk.getX() + ":" + chunk.getZ();
    }

    private int countBlocks(Chunk chunk, Material material) {
        int count = 0;
        int minY = chunk.getWorld().getMinHeight();
        int maxY = chunk.getWorld().getMaxHeight();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    if (chunk.getBlock(x, y, z).getType() == material) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private int countTnt(Chunk chunk) {
        int count = 0;
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof TNTPrimed) {
                count++;
            }
        }
        return count;
    }

    private void sendInfo(CommandSender sender) {
        FileConfiguration config = getConfig();
        sender.sendMessage(color("&8&m--------------------------------"));
        sender.sendMessage(color(config.getString("server.prefix", "&8[&cNoLaw&8]") + " &7— честная анархия без читов"));
        sender.sendMessage(color("&fПравило: &e" + config.getString("server.rule")));
        sender.sendMessage(color("&fTelegram: &b" + config.getString("server.telegram")));
        sender.sendMessage(color("&7Нет /home, /tpa, приватов и pay-to-win."));
        sender.sendMessage(color("&8&m--------------------------------"));
    }

    private void alertOperators(Player suspect, String check, String details) {
        if (!getConfig().getBoolean("alerts.enabled", true)) {
            return;
        }

        String cooldownKey = suspect.getUniqueId() + ":" + check;
        long now = System.currentTimeMillis();
        long lastAlert = alertCooldowns.getOrDefault(cooldownKey, 0L);
        if (now - lastAlert < ALERT_COOLDOWN_MILLIS) {
            return;
        }
        alertCooldowns.put(cooldownKey, now);

        int violations = violationCounts.merge(suspect.getUniqueId(), 1, Integer::sum);
        String location = formatLocation(suspect.getLocation());
        String message = color(getConfig().getString("alerts.prefix", "&8[&cNoLawAC&8]")
                + " &cВозможный чит/некорректная игра: &f" + suspect.getName()
                + " &7| &e" + check
                + " &7| &f" + details
                + " &7| VL: &c" + violations
                + " &7| " + location);

        boolean delivered = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("plahen.admin") || (getConfig().getBoolean("alerts.notify-operators", true) && player.isOp())) {
                player.sendMessage(message);
                delivered = true;
            }
        }

        getLogger().warning(ChatColor.stripColor(message));
        int reminderEvery = Math.max(1, getConfig().getInt("alerts.repeated-violations-reminder-every", 4));
        if (delivered && violations % reminderEvery == 0) {
            String reminder = color(getConfig().getString("alerts.prefix", "&8[&cNoLawAC&8]")
                    + " &6Проверь игрока &f" + suspect.getName()
                    + "&6: много предупреждений, возможны читы или неправильная игра.");
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("plahen.admin") || (getConfig().getBoolean("alerts.notify-operators", true) && player.isOp())) {
                    player.sendMessage(reminder);
                }
            }
        }
    }

    private String formatLocation(Location location) {
        return location.getWorld().getName() + " "
                + location.getBlockX() + " "
                + location.getBlockY() + " "
                + location.getBlockZ();
    }

    private boolean isProtectedSpawn(Location location) {
        if (!getConfig().getBoolean("spawn.protection-enabled", true)) {
            return false;
        }
        Location center = spawnCenter();
        if (center == null || !center.getWorld().equals(location.getWorld())) {
            return false;
        }
        return center.distanceSquared(location) <= Math.pow(getConfig().getDouble("spawn.radius", 48.0D), 2);
    }

    private Location spawnCenter() {
        String worldName = getConfig().getString("spawn.world", "");
        World world = worldName.isBlank() ? Bukkit.getWorlds().getFirst() : Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, getConfig().getDouble("spawn.x"), getConfig().getDouble("spawn.y"), getConfig().getDouble("spawn.z"));
    }

    private void initializeSpawnCenter() {
        if (!getConfig().getBoolean("spawn.set-center-from-world-spawn-on-first-run", true) || !getConfig().getString("spawn.world", "").isBlank()) {
            return;
        }
        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        if (world != null) {
            saveSpawnCenter(world.getSpawnLocation());
        }
    }

    private void saveSpawnCenter(Location location) {
        getConfig().set("spawn.world", location.getWorld().getName());
        getConfig().set("spawn.x", location.getX());
        getConfig().set("spawn.y", location.getY());
        getConfig().set("spawn.z", location.getZ());
        saveConfig();
    }

    private String pluginMessage(String message) {
        return color(getConfig().getString("server.prefix", "&8[&cNoLaw&8]") + " " + message);
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
