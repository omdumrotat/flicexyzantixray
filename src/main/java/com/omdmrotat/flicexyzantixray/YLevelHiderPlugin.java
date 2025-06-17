package com.omdmrotat.flicexyzantixray; // Your package

// PacketEvents v2.x imports - using com.github.retrooper.packetevents
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI; // Base API interface
import com.github.retrooper.packetevents.event.PacketListener; // Import PacketListener interface
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.PacketReceiveEvent; // Import for PacketListener interface

import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column; // Import the Column class
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.PacketWrapper; // Generic wrapper
// Import the specific wrapper class we identified
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange; // Import for BlockChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange; // Import for MultiBlockChange
import com.github.retrooper.packetevents.util.Vector3i; // Import for Vector3i

import com.github.retrooper.packetevents.protocol.packettype.PacketType;


import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
// import org.bukkit.event.Listener; // Already in main class
import org.bukkit.event.player.PlayerChangedWorldEvent; // Import for world change event
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class YLevelHiderPlugin extends JavaPlugin implements org.bukkit.event.Listener, CommandExecutor, TabCompleter {

    public final Map<UUID, Boolean> playerHiddenState = new ConcurrentHashMap<>();
    private final Map<UUID, Long> refreshCooldowns = new ConcurrentHashMap<>();
    private final Set<UUID> internallyTeleporting = ConcurrentHashMap.newKeySet();
    private static YLevelHiderPlugin instance;
    private WrappedBlockState airState;
    private int airStateGlobalId = 0;
    private boolean debugMode = false;
    private int refreshCooldownMillis = 3000;
    private Set<String> whitelistedWorlds = new HashSet<>();

    public static YLevelHiderPlugin getInstance() {
        return instance;
    }

    private void debugLog(String message) {
        if (debugMode) {
            getLogger().info("[YLevelHider DEBUG] " + message);
        }
    }

    private void infoLog(String message) {
        getLogger().info("[YLevelHider] " + message);
    }


    private void loadConfigValues() {
        saveDefaultConfig();
        reloadConfig();
        FileConfiguration config = getConfig();

        List<String> worldsFromConfig = config.getStringList("whitelisted-worlds");
        if (worldsFromConfig == null) {
            worldsFromConfig = new ArrayList<>();
            getLogger().warning("[YLevelHider] 'whitelisted-worlds' list not found in config.yml. Using empty list.");
        }
        this.whitelistedWorlds = new HashSet<>(worldsFromConfig);
        debugLog("Loaded whitelisted worlds: " + this.whitelistedWorlds);

        int cooldownSeconds = config.getInt("refresh-cooldown-seconds", 3);
        // If the path doesn't exist, create it with the default value.
        if (!config.contains("refresh-cooldown-seconds")) {
            config.set("refresh-cooldown-seconds", 3);
            saveConfig();
        }
        this.refreshCooldownMillis = cooldownSeconds * 1000;
        infoLog("Refresh cooldown set to " + cooldownSeconds + " seconds (" + this.refreshCooldownMillis + "ms).");
    }

    public boolean isWorldWhitelisted(String worldName) {
        if (worldName == null) return false;
        return whitelistedWorlds.contains(worldName);
    }


    @Override
    public void onLoad() {
        instance = this;
        infoLog("onLoad() called.");
        PacketEventsAPI packetEventsAPI = PacketEvents.getAPI();
        if (packetEventsAPI == null) {
            getLogger().severe("[YLevelHider] PacketEvents.getAPI() returned null even before load(). This indicates a critical issue with the PacketEvents library setup or classpath.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        packetEventsAPI.load();
        if (!packetEventsAPI.isLoaded()) {
            getLogger().severe("[YLevelHider] PacketEvents API failed to load correctly after packetEventsAPI.load().");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        infoLog("PacketEvents API loaded successfully in onLoad.");
    }

    @Override
    public void onEnable() {
        infoLog("onEnable() called.");
        loadConfigValues();

        final PacketEventsAPI packetEventsAPI = PacketEvents.getAPI();
        if (packetEventsAPI == null || !packetEventsAPI.isLoaded()) {
            getLogger().severe("[YLevelHider] PacketEvents API not available or not loaded in onEnable. YLevelHider will not function.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        debugLog("PacketEvents API confirmed available and loaded in onEnable.");

        try {
            airState = WrappedBlockState.getByString("minecraft:air");
            if (airState == null) {
                throw new IllegalStateException("WrappedBlockState.getByString(\"minecraft:air\") returned null.");
            }
            airStateGlobalId = airState.getGlobalId();
            debugLog("AIR block state initialized successfully. Global ID: " + airStateGlobalId);
        } catch (Exception e) {
            getLogger().severe("[YLevelHider] Failed to get WrappedBlockState for AIR: " + e.getMessage());
            airState = null;
        }

        if (airState == null) {
            getLogger().severe("[YLevelHider] Could not initialize AIR block state. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }


        packetEventsAPI.getSettings()
                .checkForUpdates(true);
        debugLog("PacketEvents settings configured.");

        packetEventsAPI.getEventManager().registerListener(new ChunkPacketListenerPE(this), PacketListenerPriority.NORMAL);
        debugLog("ChunkPacketListenerPE registered.");
        Bukkit.getServer().getPluginManager().registerEvents(this, this);
        debugLog("Bukkit PlayerListeners (this class) registered.");

        this.getCommand("ylevelhiderdebug").setExecutor(this);
        this.getCommand("ylevelhiderreload").setExecutor(this);
        this.getCommand("ylevelhiderworld").setExecutor(this);
        this.getCommand("ylevelhiderworld").setTabCompleter(this);
        debugLog("Commands registered.");


        Bukkit.getScheduler().runTask(this, () -> {
            if (this.isEnabled() && packetEventsAPI.isLoaded()) {
                packetEventsAPI.init();
                infoLog("PacketEvents.init() called via scheduler.");
            } else if (!this.isEnabled()){
                getLogger().warning("[YLevelHider] Plugin was disabled before PacketEvents.init() could be called via scheduler.");
            } else {
                getLogger().warning("[YLevelHider] PacketEvents API was not loaded when scheduled init task ran.");
            }
        });


        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                debugLog("Processing online player in onEnable: " + player.getName());
                if (isWorldWhitelisted(player.getWorld().getName())) {
                    debugLog("Handling initial state for already online player in whitelisted world: " + player.getName());
                    handlePlayerInitialState(player, false);
                } else {
                    debugLog("Skipping initial state for " + player.getName() + " - world '" + player.getWorld().getName() + "' not whitelisted.");
                }
            }
        } catch (Exception e) {
            getLogger().severe("[YLevelHider] Exception during online player loop in onEnable: " + e.getMessage());
            e.printStackTrace();
        }


        getLogger().info(getName() + " has been enabled. Debug mode is currently: " + (debugMode ? "ON" : "OFF"));
        getLogger().info("[YLevelHider] Active in worlds: " + whitelistedWorlds);
    }

    @Override
    public void onDisable() {
        infoLog("onDisable() called.");
        if (PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded()) {
            PacketEvents.getAPI().terminate();
            debugLog("PacketEvents API terminated.");
        }
        playerHiddenState.clear();
        getLogger().info(getName() + " has been disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase();
        switch (commandName) {
            case "ylevelhiderdebug":
                if (!sender.hasPermission("ylevelhider.admin")) {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                    return true;
                }
                debugMode = !debugMode;
                String status = debugMode ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF";
                sender.sendMessage(ChatColor.YELLOW + "[YLevelHider] Debug mode is now " + status + ChatColor.YELLOW + ".");
                getLogger().info("[YLevelHider] Debug mode toggled to " + (debugMode ? "ON" : "OFF") + " by " + sender.getName());
                return true;

            case "ylevelhiderreload":
                if (!sender.hasPermission("ylevelhider.admin")) {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                    return true;
                }
                loadConfigValues();
                sender.sendMessage(ChatColor.GREEN + "[YLevelHider] Configuration reloaded. Whitelisted worlds: " + whitelistedWorlds);
                getLogger().info("[YLevelHider] Configuration reloaded by " + sender.getName() + ". Whitelisted worlds: " + whitelistedWorlds);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (isWorldWhitelisted(p.getWorld().getName())) {
                        handlePlayerInitialState(p, true);
                    } else {
                        if (playerHiddenState.remove(p.getUniqueId()) != null) {
                            refreshFullView(p);
                        }
                    }
                }
                return true;

            case "ylevelhiderworld":
                if (!sender.hasPermission("ylevelhider.admin")) {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                    return true;
                }
                if (args.length < 1) {
                    sender.sendMessage(ChatColor.RED + "Usage: /ylevelhiderworld <list|add|remove> [worldName]");
                    return true;
                }
                String subCommand = args[0].toLowerCase();
                if (subCommand.equals("list")) {
                    sender.sendMessage(ChatColor.YELLOW + "Whitelisted worlds: " + ChatColor.WHITE + String.join(", ", whitelistedWorlds));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /ylevelhiderworld <add|remove> <worldName>");
                    return true;
                }
                String worldName = args[1];
                World targetWorld = Bukkit.getWorld(worldName);
                if (targetWorld == null && (subCommand.equals("add"))) {
                    sender.sendMessage(ChatColor.RED + "World '" + worldName + "' not found.");
                    return true;
                }

                if (subCommand.equals("add")) {
                    if (whitelistedWorlds.add(worldName)) {
                        getConfig().set("whitelisted-worlds", new ArrayList<>(whitelistedWorlds));
                        saveConfig();
                        sender.sendMessage(ChatColor.GREEN + "World '" + worldName + "' added to the whitelist.");
                        getLogger().info("World '" + worldName + "' added to whitelist by " + sender.getName());
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (p.getWorld().getName().equals(worldName)) {
                                handlePlayerInitialState(p, true);
                            }
                        }
                    } else {
                        sender.sendMessage(ChatColor.YELLOW + "World '" + worldName + "' is already whitelisted.");
                    }
                } else if (subCommand.equals("remove")) {
                    if (whitelistedWorlds.remove(worldName)) {
                        getConfig().set("whitelisted-worlds", new ArrayList<>(whitelistedWorlds));
                        saveConfig();
                        sender.sendMessage(ChatColor.GREEN + "World '" + worldName + "' removed from the whitelist.");
                        getLogger().info("World '" + worldName + "' removed from whitelist by " + sender.getName());
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (p.getWorld().getName().equals(worldName)) {
                                playerHiddenState.remove(p.getUniqueId());
                                refreshFullView(p);
                                debugLog("Reset hidden state and refreshed chunks for " + p.getName() + " in now non-whitelisted world " + worldName);
                            }
                        }
                    } else {
                        sender.sendMessage(ChatColor.YELLOW + "World '" + worldName + "' was not in the whitelist.");
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "Unknown sub-command. Usage: /ylevelhiderworld <list|add|remove> [worldName]");
                }
                return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("ylevelhiderworld")) {
            if (args.length == 1) {
                return StringUtil.copyPartialMatches(args[0], Arrays.asList("list", "add", "remove"), new ArrayList<>());
            } else if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
                List<String> worldNames = Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.toList());
                if (args[0].equalsIgnoreCase("remove")) {
                    List<String> removableWorlds = new ArrayList<>(whitelistedWorlds);
                    return StringUtil.copyPartialMatches(args[1], removableWorlds, new ArrayList<>());
                }
                return StringUtil.copyPartialMatches(args[1], worldNames, new ArrayList<>());
            }
        }
        return Collections.emptyList();
    }

    public void handlePlayerInitialState(Player player, boolean immediateRefresh) {
        if (!isWorldWhitelisted(player.getWorld().getName())) {
            debugLog("handlePlayerInitialState for " + player.getName() + " skipped, world " + player.getWorld().getName() + " not whitelisted.");
            boolean wasPresent = playerHiddenState.remove(player.getUniqueId()) != null;
            if (wasPresent && immediateRefresh) {
                debugLog("Player " + player.getName() + " moved to non-whitelisted world, had state, refreshing immediately with full view.");
                refreshFullView(player);
            }
            return;
        }
        debugLog("handlePlayerInitialState for " + player.getName() + " in whitelisted world " + player.getWorld().getName() + (immediateRefresh ? " (immediate refresh)" : " (delayed refresh allowed)"));
        double currentY = player.getLocation().getY();
        boolean initialStateIsHidden = currentY >= 31.0;
        playerHiddenState.put(player.getUniqueId(), initialStateIsHidden);
        debugLog("Player " + player.getName() + " at Y=" + String.format("%.2f", currentY) + ". Initial hidden state: " + initialStateIsHidden);

        if (initialStateIsHidden) {
            // When activating hide state (either initially or through world change/reload),
            // we rely on the packet listener for new chunks.
            // For already visible chunks, a full refresh is needed if immediateRefresh is true.
            if (immediateRefresh) {
                debugLog("Initial state is hidden for " + player.getName() + ". Refreshing full view immediately.");
                refreshFullView(player);
            } else {
                debugLog("Initial state is hidden for " + player.getName() + ". Relying on packet listener for new/refreshed chunks.");
                // No delayed refresh here to avoid potential lag on join/enable if many players are affected.
                // The view will update as chunks are naturally sent or player moves.
            }
        } else {
            // If the new state is NOT hidden, refresh to ensure everything is visible.
            if (immediateRefresh) {
                debugLog("Player " + player.getName() + " new state is NOT hidden. Refreshing full view immediately to ensure normal view.");
                refreshFullView(player);
            }
        }
    }

    public void refreshFullView(Player player) {
        debugLog("refreshFullView called for " + player.getName() + " in world " + player.getWorld().getName());
        performRefresh(player, Bukkit.getServer().getViewDistance());
    }


    private void performRefresh(Player player, int radiusChunks) {
        debugLog("performRefresh executing for " + player.getName() + " with radius " + radiusChunks);
        if (!player.isOnline()) {
            debugLog("Player " + player.getName() + " is offline in performRefresh. Skipping.");
            return;
        }
        if (!isWorldWhitelisted(player.getWorld().getName())) {
            debugLog("performRefresh skipped for " + player.getName() + ", world " + player.getWorld().getName() + " not whitelisted.");
            return;
        }

        if (!Bukkit.isPrimaryThread()) {
            final int finalRadius = radiusChunks;
            debugLog("Not on main thread. Scheduling performRefresh for " + player.getName() + " with radius " + finalRadius);
            Bukkit.getScheduler().runTask(this, () -> performRefresh(player, finalRadius));
            return;
        }

        World world = player.getWorld();
        Location loc = player.getLocation();
        int playerChunkX = loc.getBlockX() >> 4;
        int playerChunkZ = loc.getBlockZ() >> 4;
        int refreshedCount = 0;
        for (int cx = playerChunkX - radiusChunks; cx <= playerChunkX + radiusChunks; cx++) {
            for (int cz = playerChunkZ - radiusChunks; cz <= playerChunkZ + radiusChunks; cz++) {
                world.refreshChunk(cx, cz);
                refreshedCount++;
            }
        }
        debugLog("Refreshed " + refreshedCount + " chunks (radius " + radiusChunks + ") around " + player.getName());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        infoLog("onPlayerJoin CALLED for: " + player.getName() + " in world " + player.getWorld().getName());
        if (isWorldWhitelisted(player.getWorld().getName())) {
            handlePlayerInitialState(player, false);
        } else {
            debugLog("Player " + player.getName() + " joined non-whitelisted world. No initial state handling.");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        infoLog("onPlayerQuit CALLED for: " + player.getName());
        refreshCooldowns.remove(player.getUniqueId());
        playerHiddenState.remove(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World fromWorld = event.getFrom();
        World toWorld = player.getWorld();

        infoLog("PlayerChangedWorldEvent for " + player.getName() + " from " + fromWorld.getName() + " to " + toWorld.getName());

        if (isWorldWhitelisted(toWorld.getName())) {
            debugLog("Player " + player.getName() + " entered whitelisted world " + toWorld.getName() + ". Handling initial state with immediate (full) refresh.");
            handlePlayerInitialState(player, true);
        } else {
            boolean wasHidden = playerHiddenState.remove(player.getUniqueId()) != null;
            if (wasHidden) {
                debugLog("Player " + player.getName() + " entered non-whitelisted world " + toWorld.getName() + ". State cleared, refreshing full view immediately.");
                refreshFullView(player);
            } else {
                debugLog("Player " + player.getName() + " entered non-whitelisted world " + toWorld.getName() + ". No prior hidden state to clear or already normal.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        // Fix for recursion: If this teleport was initiated by our own plugin, ignore it.
        if (internallyTeleporting.contains(event.getPlayer().getUniqueId())) {
            return;
        }

        Player player = event.getPlayer();
        Location to = event.getTo();

        if (to == null) return;

        boolean toWorldIsWhitelisted = isWorldWhitelisted(to.getWorld().getName());
        boolean fromWorldIsWhitelisted = isWorldWhitelisted(event.getFrom().getWorld().getName());

        if (!toWorldIsWhitelisted) {
            // Handle teleporting OUT of a whitelisted world.
            if (fromWorldIsWhitelisted && playerHiddenState.remove(player.getUniqueId()) != null) {
                // Schedule the refresh for after the teleport is complete.
                Bukkit.getScheduler().runTask(this, () -> {
                    if (player.isOnline()) {
                        debugLog("Player " + player.getName() + " teleported out of a whitelisted world. Refreshing view.");
                        refreshFullView(player);
                    }
                });
            }
            return;
        }

        UUID playerUUID = player.getUniqueId();
        double destY = to.getY();

        boolean oldStateIsHidden = playerHiddenState.getOrDefault(playerUUID, destY >= 31.0);
        boolean newStateIsHidden = destY >= 31.0;

        if (oldStateIsHidden == newStateIsHidden) {
            return; // No state change, so no special handling is needed.
        }

        // This is the critical race condition: teleporting from a HIDING state to a NOT HIDING state where chunks may be unloaded.
        if (!newStateIsHidden) { // Transitioning TO a non-hiding state (Hiding -> Visible)
            debugLog("Intercepting teleport for " + player.getName() + " from HIDING to NOT HIDING state. Delaying by 1 tick to prevent void bug.");
            playerHiddenState.put(playerUUID, false); // Update state immediately
            event.setCancelled(true); // Cancel original event
            // Schedule a new teleport for the next tick. By then, the state is correct, and packets will be generated properly.
            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) return;
                internallyTeleporting.add(playerUUID);
                try {
                    player.teleport(to);
                } finally {
                    internallyTeleporting.remove(playerUUID);
                }
            });
        } else { // Transitioning TO a hiding state (Visible -> Hiding)
            debugLog("Player " + player.getName() + " teleporting to a HIDING state. Updating state immediately.");
            playerHiddenState.put(playerUUID, true);
            // Let the teleport proceed. The packet listener will now correctly hide the new chunks being sent.
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        debugLog("onPlayerMove CALLED for " + player.getName() + " in world " + player.getWorld().getName());

        if (!isWorldWhitelisted(player.getWorld().getName())) {
            if (playerHiddenState.containsKey(player.getUniqueId())) {
                boolean wasHidden = playerHiddenState.remove(player.getUniqueId()) != null;
                if (wasHidden) {
                    debugLog(player.getName() + " moved within/to non-whitelisted world " + player.getWorld().getName() + ". Resetting state and refreshing full view.");
                    refreshFullView(player);
                }
            }
            return;
        }

        debugLog("onPlayerMove in whitelisted world " + player.getWorld().getName() + " for " + player.getName());

        Location to = event.getTo();
        Location from = event.getFrom();

        if (to == null) {
            debugLog("onPlayerMove: 'to' location is null. Skipping.");
            return;
        }
        if (from.getBlockY() == to.getBlockY()) {
            return;
        }

        debugLog("onPlayerMove: Y-block CHANGED for " + player.getName());

        double currentY = to.getY();
        UUID playerUUID = player.getUniqueId();

        boolean oldStateIsHidden = this.playerHiddenState.getOrDefault(playerUUID, currentY >= 31.0);
        boolean newStateIsHidden;

        if (currentY >= 31.0) {
            newStateIsHidden = true;
        } else if (currentY <= 30.0) {
            newStateIsHidden = false;
        } else {
            newStateIsHidden = oldStateIsHidden;
        }

        debugLog(String.format("PlayerMove Details: %s, FromY: %.2f (BlockY:%d), ToY: %.2f (BlockY:%d), OldStateHidden: %b, NewStateHidden: %b",
                player.getName(), from.getY(), from.getBlockY(), to.getY(), to.getBlockY(), oldStateIsHidden, newStateIsHidden));


        if (newStateIsHidden != oldStateIsHidden) {
            long currentTime = System.currentTimeMillis();
            long expirationTime = refreshCooldowns.getOrDefault(playerUUID, 0L);

            this.playerHiddenState.put(playerUUID, newStateIsHidden);

            if (currentTime < expirationTime) {
                // Cooldown is active, so only update the state and skip the refresh.
                debugLog("State changed for " + player.getName() + ". New hidden state: " + newStateIsHidden + ". Refresh skipped due to active cooldown.");
            } else {
                // Cooldown has expired, perform a refresh and set a new cooldown.
                debugLog("State changed for " + player.getName() + ". New hidden state: " + newStateIsHidden + ". Refreshing full view at Y=" + String.format("%.2f", currentY) + " and starting cooldown.");
                this.refreshFullView(player);
                refreshCooldowns.put(playerUUID, currentTime + refreshCooldownMillis);
            }
        } else {
            debugLog("State NOT changed for " + player.getName() + ". Current hidden state: " + newStateIsHidden);
        }
    }

    public WrappedBlockState getAirState() {
        return airState;
    }

    public int getAirStateGlobalId() {
        return airStateGlobalId;
    }

    public boolean isDebugMode() {
        return debugMode;
    }
}

class ChunkPacketListenerPE implements PacketListener {
    private final YLevelHiderPlugin plugin;

    public ChunkPacketListenerPE(YLevelHiderPlugin plugin) {
        this.plugin = plugin;
    }

    private void listenerDebugLog(String message) {
        if (plugin.isDebugMode()) {
            plugin.getLogger().info("[YLevelHider DEBUG][PacketListener] " + message);
        }
    }


    @Override
    public void onPacketSend(PacketSendEvent event) {
        listenerDebugLog("onPacketSend CALLED. PacketType: " + event.getPacketType().getName());

        User user = event.getUser();
        if (user == null) {
            listenerDebugLog("User object is null in onPacketSend. Skipping.");
            return;
        }

        UUID userUUID = user.getUUID();
        if (userUUID == null) {
            return;
        }

        Player player = Bukkit.getPlayer(userUUID);
        if (player == null || !player.isOnline()) {
            listenerDebugLog("Bukkit.getPlayer(uuid) returned null or player offline for packet type: " + event.getPacketType().getName());
            return;
        }

        // Add a null-check for the player's world to prevent errors during world change/login.
        World playerWorld = player.getWorld();
        if (playerWorld == null || !plugin.isWorldWhitelisted(playerWorld.getName())) {
            return;
        }

        listenerDebugLog("Processing packet for " + player.getName() + " in whitelisted world " + player.getWorld().getName() + ". PacketType: " + event.getPacketType().getName());

        // Handle CHUNK_DATA
        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            handleChunkDataPacket(event, player);
        }
        // Handle BLOCK_CHANGE
        else if (event.getPacketType() == PacketType.Play.Server.BLOCK_CHANGE) {
            handleBlockChangePacket(event, player);
        }
        // Handle MULTI_BLOCK_CHANGE
        else if (event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            handleMultiBlockChangePacket(event, player);
        }
    }

    private void handleChunkDataPacket(PacketSendEvent event, Player player) {
        listenerDebugLog("Intercepted CHUNK_DATA packet for " + player.getName());
        boolean shouldHide = plugin.playerHiddenState.getOrDefault(player.getUniqueId(), false);
        listenerDebugLog("Player: " + player.getName() + ", shouldHide: " + shouldHide + " (from playerHiddenState: " + plugin.playerHiddenState.get(player.getUniqueId()) + ")");

        if (shouldHide) {
            WrappedBlockState air = plugin.getAirState();
            if (air == null) {
                plugin.getLogger().warning("[YLevelHider][PacketListener] AIR block state is not available. Cannot modify chunk for " + player.getName());
                return;
            }
            listenerDebugLog("Proceeding to modify CHUNK_DATA for " + player.getName());

            WrapperPlayServerChunkData chunkDataWrapper = null;
            try {
                chunkDataWrapper = new WrapperPlayServerChunkData(event);
            } catch (Exception e) {
                plugin.getLogger().severe("[YLevelHider][PacketListener] Error creating WrapperPlayServerChunkData: " + e.getMessage());
                e.printStackTrace();
                return;
            }

            Column column = null;
            BaseChunk[] chunkSections = null;

            try {
                column = chunkDataWrapper.getColumn();
                if (column == null) {
                    plugin.getLogger().warning("[YLevelHider][PacketListener] WrapperPlayServerChunkData.getColumn() returned null for player " + player.getName());
                    return;
                }
                chunkSections = column.getChunks();
                listenerDebugLog("Got column for " + player.getName() + " X:" + column.getX() + " Z:" + column.getZ() + " Sections:" + (chunkSections != null ? chunkSections.length : "null"));
            } catch (Exception e) {
                plugin.getLogger().severe("[YLevelHider][PacketListener] Error accessing Column or its data (X, Z, or Chunks): " + e.getMessage());
                e.printStackTrace();
                return;
            }

            if (chunkSections == null) {
                plugin.getLogger().warning("[YLevelHider][PacketListener] Retrieved chunkSections is null from Column object for player: " + player.getName());
                return;
            }

            World world = player.getWorld();
            if (world == null) {
                // This is a defensive check; we shouldn't get here if the check in onPacketSend is working, but it's safe to have.
                return;
            }
            int worldMinY = world.getMinHeight();
            boolean modified = false;

            for (int sectionIndex = 0; sectionIndex < chunkSections.length; sectionIndex++) {
                BaseChunk section = chunkSections[sectionIndex];
                if (section == null || section.isEmpty()) {
                    continue;
                }
                int sectionMinWorldY = worldMinY + (sectionIndex * 16);
                for (int yInSection = 0; yInSection < 16; yInSection++) {
                    int currentWorldY = sectionMinWorldY + yInSection;
                    if (currentWorldY <= 16) {
                        for (int relX = 0; relX < 16; relX++) {
                            for (int relZ = 0; relZ < 16; relZ++) {
                                try {
                                    WrappedBlockState currentState = section.get(relX, yInSection, relZ);
                                    if (currentState != null && !currentState.equals(air)) {
                                        listenerDebugLog("CHUNK_DATA: Changing block at [" + relX + "," + yInSection + "," + relZ + "] in section " + sectionIndex +
                                                " (world Y " + currentWorldY + ") from " + currentState.getType().getName() + " to AIR for player " + player.getName());
                                        section.set(relX, yInSection, relZ, air);
                                        modified = true;
                                    }
                                } catch (Exception e) {
                                    listenerDebugLog("Error setting block in CHUNK_DATA section " + sectionIndex + " at (" + relX + "," + yInSection + "," + relZ + "): " + e.getMessage());
                                }
                            }
                        }
                    }
                }
            }

            if (modified) {
                try {
                    chunkDataWrapper.setIgnoreOldData(true);
                    listenerDebugLog("Set ignoreOldData=true for CHUNK_DATA to " + player.getName());
                } catch (Exception e) {
                    plugin.getLogger().warning("[YLevelHider][PacketListener] Failed to set ignoreOldData on WrapperPlayServerChunkData: " + e.getMessage());
                }
                event.markForReEncode(true);
                listenerDebugLog("CHUNK_DATA for " + player.getName() + " was modified to hide blocks at Y<=16 and marked for re-encode.");
            } else {
                listenerDebugLog("CHUNK_DATA for " + player.getName() + " processed, but no blocks were modified (shouldHide=" + shouldHide + ").");
            }
        } else {
            listenerDebugLog("CHUNK_DATA for " + player.getName() + ", shouldHide is false. No modification.");
        }
    }

    private void handleBlockChangePacket(PacketSendEvent event, Player player) {
        listenerDebugLog("Intercepted BLOCK_CHANGE packet for " + player.getName());
        boolean shouldHide = plugin.playerHiddenState.getOrDefault(player.getUniqueId(), false);
        if (shouldHide) {
            WrappedBlockState air = plugin.getAirState();
            if (air == null) return;

            WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
            Vector3i blockPos = wrapper.getBlockPosition();

            if (blockPos != null && blockPos.getY() <= 16) {
                WrappedBlockState currentState = wrapper.getBlockState();
                if (currentState != null && !currentState.equals(air)) {
                    listenerDebugLog("BLOCK_CHANGE: Changing block at " + blockPos.toString() + " from " + currentState.getType().getName() + " to AIR for " + player.getName());
                    wrapper.setBlockState(air);
                    event.markForReEncode(true);
                }
            }
        }
    }

    private void handleMultiBlockChangePacket(PacketSendEvent event, Player player) {
        listenerDebugLog("Intercepted MULTI_BLOCK_CHANGE packet for " + player.getName());
        boolean shouldHide = plugin.playerHiddenState.getOrDefault(player.getUniqueId(), false);
        if (shouldHide) {
            WrappedBlockState air = plugin.getAirState();
            if (air == null) return;

            WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);
            boolean modifiedInPacket = false;

            WrapperPlayServerMultiBlockChange.EncodedBlock[] records = wrapper.getBlocks();
            if (records == null) {
                plugin.getLogger().warning("[YLevelHider][PacketListener] MULTI_BLOCK_CHANGE: Records (getBlocks) are null. Cannot process.");
                return;
            }

            listenerDebugLog("MULTI_BLOCK_CHANGE: Processing " + records.length + " records.");


            for (WrapperPlayServerMultiBlockChange.EncodedBlock record : records) {
                if (record == null) continue;

                int currentWorldY = record.getY();
                int currentBlockId = record.getBlockId();

                if (currentWorldY <= 16) {
                    int airId = plugin.getAirStateGlobalId();
                    if (currentBlockId != airId) {
                        listenerDebugLog("MULTI_BLOCK_CHANGE: Changing block at global ("+record.getX()+","+currentWorldY+","+record.getZ()+") from ID " + currentBlockId + " to AIR for " + player.getName());
                        try {
                            record.setBlockId(airId);
                            modifiedInPacket = true;
                        } catch (Exception e) {
                            listenerDebugLog("MULTI_BLOCK_CHANGE: Failed to setBlockId on EncodedBlock record. Error: " + e.getMessage());
                        }
                    }
                }
            }

            if (modifiedInPacket) {
                event.markForReEncode(true);
                listenerDebugLog("MULTI_BLOCK_CHANGE for " + player.getName() + " was modified and marked for re-encode.");
            }
        }
    }


    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // This method is required by the PacketListener interface.
    }
}