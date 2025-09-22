package com.omdmrotat.flicexyzantixray; // Your package

// PacketEvents v2.x imports - using com.github.retrooper.packetevents
import java.util.ArrayList;
import java.util.Arrays; // Base API interface
import java.util.Collections; // Import PacketListener interface
import java.util.HashSet;
import java.util.List;
import java.util.Map; // Import for PacketListener interface
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap; // Import the Column class
import java.util.stream.Collectors;

import org.bukkit.Bukkit; // Generic wrapper
import org.bukkit.ChatColor;
import org.bukkit.World; // Import for BlockChange
import org.bukkit.command.Command; // Import for MultiBlockChange
import org.bukkit.command.CommandExecutor; // Import for entity spawning
import org.bukkit.command.CommandSender; // Import for living entity spawning
import org.bukkit.command.TabCompleter; // Import for entity destruction
import org.bukkit.configuration.file.FileConfiguration; // Import for Vector3i
import org.bukkit.entity.Player; // Import for Vector3d
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.StringUtil;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI; // Import for world change event
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent; // Additional event for respawning
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnLivingEntity;
import com.tcoded.folialib.FoliaLib;

public class YLevelHiderPlugin extends JavaPlugin implements org.bukkit.event.Listener, CommandExecutor, TabCompleter {

    public final Map<UUID, Boolean> playerHiddenState = new ConcurrentHashMap<>();
    private final Map<UUID, Long> refreshCooldowns = new ConcurrentHashMap<>();
    private final Set<UUID> internallyTeleporting = ConcurrentHashMap.newKeySet();
    // Track chunks with fake deepslate blocks for each player
    final Map<UUID, Set<String>> playerFakeChunks = new ConcurrentHashMap<>();
    private static YLevelHiderPlugin instance;
    private WrappedBlockState airState;
    private int airStateGlobalId = 0;
    private WrappedBlockState deepslateState;
    private int deepslateStateGlobalId = 0;
    private boolean debugMode = false;
    private int refreshCooldownMillis = 3000;
    private Set<String> whitelistedWorlds = new HashSet<>();
    private BukkitTask stateValidationTask;
    private int stateValidationIntervalSeconds = 10; // Configurable validation interval
    private BukkitTask chunkUncoverTask; // Task for checking player look direction
    private ChunkPacketListenerPE packetListener; // PacketEvents listener instance
    private com.github.retrooper.packetevents.event.PacketListenerCommon registeredListener; // The registered listener reference
    private FoliaLib foliaLib;
    volatile boolean pluginDisabling = false; // Flag to track if plugin is being disabled
    
    // Enhanced anti-xray configuration
    boolean hideEntitiesBelowY30 = true;
    String fakeBlockMaterial = "DEEPSLATE";
    int lookDetectionRange = 5;
    int lookCheckIntervalTicks = 10;

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
        
        // Load state validation interval
        int validationSeconds = config.getInt("state-validation-interval-seconds", 10);
        if (!config.contains("state-validation-interval-seconds")) {
            config.set("state-validation-interval-seconds", 10);
            saveConfig();
        }
        this.stateValidationIntervalSeconds = validationSeconds;
        infoLog("State validation interval set to " + validationSeconds + " seconds (for Folia compatibility).");
        
        // Load enhanced anti-xray settings
        this.hideEntitiesBelowY30 = config.getBoolean("hide-entities-below-y30", true);
        if (!config.contains("hide-entities-below-y30")) {
            config.set("hide-entities-below-y30", true);
            saveConfig();
        }
        infoLog("Entity hiding below Y=30: " + (hideEntitiesBelowY30 ? "ENABLED" : "DISABLED"));
        
        this.fakeBlockMaterial = config.getString("fake-block-material", "DEEPSLATE");
        if (!config.contains("fake-block-material")) {
            config.set("fake-block-material", "DEEPSLATE");
            saveConfig();
        }
        infoLog("Fake block material set to: " + fakeBlockMaterial);
        
        this.lookDetectionRange = config.getInt("look-detection-range", 5);
        if (!config.contains("look-detection-range")) {
            config.set("look-detection-range", 5);
            saveConfig();
        }
        infoLog("Look detection range set to: " + lookDetectionRange + " blocks");
        
        this.lookCheckIntervalTicks = config.getInt("look-check-interval-ticks", 10);
        if (!config.contains("look-check-interval-ticks")) {
            config.set("look-check-interval-ticks", 10);
            saveConfig();
        }
        infoLog("Look check interval set to: " + lookCheckIntervalTicks + " ticks (" + String.format("%.1f", lookCheckIntervalTicks / 20.0) + " seconds)");
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
        
        // Load PacketEvents (don't call init() here because onLoad is executed before the plugin is enabled
        // and PacketEvents may attempt to register Bukkit listeners which requires the plugin to be enabled)
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
        if (packetEventsAPI == null) {
            getLogger().severe("[YLevelHider] PacketEvents.getAPI() returned null in onEnable. YLevelHider will not function.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Ensure PacketEvents is loaded (defensive - load() was called in onLoad but some setups may differ)
        if (!packetEventsAPI.isLoaded()) {
            debugLog("PacketEvents API was not loaded yet in onEnable; attempting to load.");
            try {
                packetEventsAPI.load();
            } catch (Throwable t) {
                getLogger().severe("[YLevelHider] Failed to load PacketEvents API in onEnable: " + t.getMessage());
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            if (!packetEventsAPI.isLoaded()) {
                getLogger().severe("[YLevelHider] PacketEvents API failed to load in onEnable.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        // Initialize FoliaLib early so we can use its scheduler where available
        try {
            foliaLib = new FoliaLib(this);
            debugLog("FoliaLib initialized in onEnable.");
        } catch (Throwable t) {
            foliaLib = null;
            debugLog("FoliaLib not available in onEnable: " + t.getMessage());
        }

        // Initialize PacketEvents and register listeners on the next tick to ensure the plugin
        // is fully enabled and avoid IllegalPluginAccessException or classloader/zip-closed errors.
        if (foliaLib != null) {
            foliaLib.getScheduler().runNextTick(wrappedTask -> {
                try {
                    if (!packetEventsAPI.isInitialized()) {
                        packetEventsAPI.init();
                        infoLog("PacketEvents API initialized successfully (deferred onEnable tick).");
                    }

                    // Configure settings and register PacketEvents listener.
                    packetEventsAPI.getSettings().checkForUpdates(true);
                    packetListener = new ChunkPacketListenerPE(this);
                    registeredListener = packetEventsAPI.getEventManager().registerListener(packetListener, PacketListenerPriority.NORMAL);
                    debugLog("ChunkPacketListenerPE registered (deferred onEnable tick).");
                } catch (Throwable t) {
                    getLogger().severe("[YLevelHider] Failed to initialize PacketEvents API or register listener on deferred tick: " + t.getMessage());
                    if (debugMode) t.printStackTrace();
                    foliaLib.getScheduler().runNextTick(innerTask -> getServer().getPluginManager().disablePlugin(this));
                }
            });
        } else {
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    if (!packetEventsAPI.isInitialized()) {
                        packetEventsAPI.init();
                        infoLog("PacketEvents API initialized successfully (deferred onEnable tick).");
                    }

                    // Configure settings and register PacketEvents listener.
                    packetEventsAPI.getSettings().checkForUpdates(true);
                    packetListener = new ChunkPacketListenerPE(this);
                    registeredListener = packetEventsAPI.getEventManager().registerListener(packetListener, PacketListenerPriority.NORMAL);
                    debugLog("ChunkPacketListenerPE registered (deferred onEnable tick).");
                } catch (Throwable t) {
                    getLogger().severe("[YLevelHider] Failed to initialize PacketEvents API or register listener on deferred tick: " + t.getMessage());
                    if (debugMode) t.printStackTrace();
                    Bukkit.getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
                }
            });
        }
        debugLog("PacketEvents initialization deferred to next server tick to avoid early listener registration.");

        try {
            airState = WrappedBlockState.getByString("minecraft:air");
            if (airState == null) {
                throw new IllegalStateException("WrappedBlockState.getByString(\"minecraft:air\") returned null.");
            }
            airStateGlobalId = airState.getGlobalId();
            debugLog("AIR block state initialized successfully. Global ID: " + airStateGlobalId);
            
            String materialName = "minecraft:" + fakeBlockMaterial.toLowerCase();
            deepslateState = WrappedBlockState.getByString(materialName);
            if (deepslateState == null) {
                throw new IllegalStateException("WrappedBlockState.getByString(\"" + materialName + "\") returned null.");
            }
            deepslateStateGlobalId = deepslateState.getGlobalId();
            debugLog(fakeBlockMaterial + " block state initialized successfully. Global ID: " + deepslateStateGlobalId);
        } catch (Exception e) {
            getLogger().severe("[YLevelHider] Failed to get WrappedBlockState for AIR or " + fakeBlockMaterial + ": " + e.getMessage());
            airState = null;
            deepslateState = null;
        }

        if (airState == null || deepslateState == null) {
            getLogger().severe("[YLevelHider] Could not initialize AIR or " + fakeBlockMaterial + " block state. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }


        packetEventsAPI.getSettings()
                .checkForUpdates(true);
        debugLog("PacketEvents settings configured.");

        packetListener = new ChunkPacketListenerPE(this);
        registeredListener = packetEventsAPI.getEventManager().registerListener(packetListener, PacketListenerPriority.NORMAL);
        debugLog("ChunkPacketListenerPE registered.");
        Bukkit.getServer().getPluginManager().registerEvents(this, this);
        debugLog("Bukkit PlayerListeners (this class) registered.");

        this.getCommand("ylevelhiderdebug").setExecutor(this);
        this.getCommand("ylevelhiderreload").setExecutor(this);
        this.getCommand("ylevelhiderworld").setExecutor(this);
        this.getCommand("ylevelhiderworld").setTabCompleter(this);
        debugLog("Commands registered.");


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
        getLogger().info("[YLevelHider] Server type detected: " + (FoliaScheduler.isFolia() ? "Folia (regionized threading)" : "Paper/Spigot (single-threaded)"));
        
        // Start periodic state validation task for Folia compatibility
        startStateValidationTask();
        
        // Start chunk uncovering task for look detection
        startChunkUncoverTask();
    }

    @Override
    public void onDisable() {
        infoLog("onDisable() called.");
        
        // Set the flag to indicate plugin is being disabled
        pluginDisabling = true;
        
        // Cancel the state validation task
        if (stateValidationTask != null) {
            stateValidationTask.cancel();
            stateValidationTask = null;
        }
        
        // Cancel the chunk uncovering task
        if (chunkUncoverTask != null) {
            chunkUncoverTask.cancel();
            chunkUncoverTask = null;
        }

        // If FoliaLib was used for scheduling, cancel all FoliaLib tasks
        if (foliaLib != null) {
            try {
                foliaLib.getScheduler().cancelAllTasks();
            } catch (Throwable t) {
                getLogger().warning("[YLevelHider] Failed to cancel FoliaLib tasks: " + t.getMessage());
            }
        }
        
        // Unregister the packet listener to prevent classloader issues
        if (registeredListener != null && PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded()) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(registeredListener);
                debugLog("ChunkPacketListenerPE unregistered.");
                registeredListener = null;
                packetListener = null;
            } catch (Exception e) {
                // If unregistering fails, log it but don't throw
                getLogger().warning("[YLevelHider] Failed to unregister packet listener: " + e.getMessage());
            }
        }
        playerHiddenState.clear();
        playerFakeChunks.clear();
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
                sender.sendMessage(ChatColor.GRAY + "[YLevelHider] Server type: " + (FoliaScheduler.isFolia() ? "Folia (regionized)" : "Paper/Spigot"));
                getLogger().info("[YLevelHider] Debug mode toggled to " + (debugMode ? "ON" : "OFF") + " by " + sender.getName());
                
                // Test scheduler functionality if sender is a player
                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    sender.sendMessage(ChatColor.BLUE + "[YLevelHider] Testing scheduler compatibility...");
                    if (foliaLib != null) {
                        foliaLib.getScheduler().runAtEntity(player, wrappedTask -> player.sendMessage(ChatColor.GREEN + "[YLevelHider] Scheduler test successful!"));
                    } else {
                        Bukkit.getScheduler().runTask(this, () -> player.sendMessage(ChatColor.GREEN + "[YLevelHider] Scheduler test successful!"));
                    }
                }
                return true;

            case "ylevelhiderreload":
                if (!sender.hasPermission("ylevelhider.admin")) {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                    return true;
                }
                
                // Cancel existing validation task before reload
                if (stateValidationTask != null) {
                    stateValidationTask.cancel();
                    stateValidationTask = null;
                }
                
                loadConfigValues();
                
                // Restart validation task with new settings
                startStateValidationTask();
                
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
            if (foliaLib != null) {
                foliaLib.getScheduler().runAtEntity(player, wrappedTask -> performRefresh(player, finalRadius));
            } else {
                Bukkit.getScheduler().runTask(this, () -> performRefresh(player, finalRadius));
            }
            return;
        }

        World world = player.getWorld();
        org.bukkit.Location loc = player.getLocation();
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
        playerFakeChunks.remove(player.getUniqueId());
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
        org.bukkit.Location to = event.getTo();

        if (to == null) return;

        boolean toWorldIsWhitelisted = isWorldWhitelisted(to.getWorld().getName());
        boolean fromWorldIsWhitelisted = isWorldWhitelisted(event.getFrom().getWorld().getName());

        // Log teleport events for better debugging in Folia
        debugLog("PlayerTeleportEvent: " + player.getName() + 
                " from " + event.getFrom().getWorld().getName() + " Y=" + String.format("%.2f", event.getFrom().getY()) +
                " to " + to.getWorld().getName() + " Y=" + String.format("%.2f", to.getY()) +
                " Cause: " + event.getCause());

        if (!toWorldIsWhitelisted) {
            // Handle teleporting OUT of a whitelisted world.
            if (fromWorldIsWhitelisted && playerHiddenState.remove(player.getUniqueId()) != null) {
                // Schedule the refresh for after the teleport is complete.
                if (foliaLib != null) {
                    foliaLib.getScheduler().runAtEntity(player, wrappedTask -> {
                        if (player.isOnline()) {
                            debugLog("Player " + player.getName() + " teleported out of a whitelisted world. Refreshing view.");
                            refreshFullView(player);
                        }
                    });
                } else {
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (player.isOnline()) {
                            debugLog("Player " + player.getName() + " teleported out of a whitelisted world. Refreshing view.");
                            refreshFullView(player);
                        }
                    });
                }
            }
            return;
        }

        UUID playerUUID = player.getUniqueId();
        double destY = to.getY();

        boolean oldStateIsHidden = playerHiddenState.getOrDefault(playerUUID, destY >= 31.0);
        boolean newStateIsHidden = destY >= 31.0;

        if (oldStateIsHidden == newStateIsHidden) {
            // Even if no state change, ensure state is correctly recorded for Folia reliability
            playerHiddenState.put(playerUUID, newStateIsHidden);
            return;
        }

        // This is the critical race condition: teleporting from a HIDING state to a NOT HIDING state where chunks may be unloaded.
        if (!newStateIsHidden) { // Transitioning TO a non-hiding state (Hiding -> Visible)
            debugLog("Intercepting teleport for " + player.getName() + " from HIDING to NOT HIDING state. Delaying by 1 tick to prevent void bug.");
            playerHiddenState.put(playerUUID, false); // Update state immediately
            event.setCancelled(true); // Cancel original event
            // Schedule a new teleport for the next tick. By then, the state is correct, and packets will be generated properly.
            if (foliaLib != null) {
                foliaLib.getScheduler().runAtEntity(player, wrappedTask -> {
                    if (!player.isOnline()) return;
                    internallyTeleporting.add(playerUUID);
                    try {
                        player.teleportAsync(to);
                    } finally {
                        internallyTeleporting.remove(playerUUID);
                    }
                });
            } else {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!player.isOnline()) return;
                    internallyTeleporting.add(playerUUID);
                    try {
                        player.teleport(to);
                    } finally {
                        internallyTeleporting.remove(playerUUID);
                    }
                });
            }
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

        org.bukkit.Location to = event.getTo();
        org.bukkit.Location from = event.getFrom();

        if (to == null) {
            debugLog("onPlayerMove: 'to' location is null. Skipping.");
            return;
        }
        
        // Check for potential missed teleportation (large distance movement)
        double distance = from.distance(to);
        if (distance > 50.0) { // Threshold for detecting potential teleportation
            debugLog("Large movement detected for " + player.getName() + " (distance: " + String.format("%.2f", distance) + "). Potential missed teleport event - handling as teleport.");
            
            // Handle this as a potential missed teleport
            UUID playerUUID = player.getUniqueId();
            double destY = to.getY();
            boolean expectedHiddenState = destY >= 31.0;
            boolean currentHiddenState = playerHiddenState.getOrDefault(playerUUID, expectedHiddenState);
            
            if (currentHiddenState != expectedHiddenState) {
                debugLog("State correction needed for potential missed teleport: " + player.getName() + 
                        " Y=" + String.format("%.2f", destY) + 
                        " Old=" + currentHiddenState + " New=" + expectedHiddenState);
                
                playerHiddenState.put(playerUUID, expectedHiddenState);
                
                // Force refresh for potential missed teleport
                long currentTime = System.currentTimeMillis();
                refreshFullView(player);
                refreshCooldowns.put(playerUUID, currentTime + refreshCooldownMillis);
                return;
            }
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
                debugLog("State changed for " + player.getName() + ". New hidden state: " + newStateIsHidden + ". Refreshing view at Y=" + String.format("%.2f", currentY) + " and starting cooldown.");
                // If we're transitioning from HIDING -> VISIBLE, only refresh a small 3x3 area
                // (radiusChunks = 1). This prevents a full view-distance refresh which reveals
                // too many chunks at once. Otherwise, keep the full refresh behavior.
                if (oldStateIsHidden && !newStateIsHidden) {
                    debugLog("Transition HIDING->VISIBLE for " + player.getName() + ", performing 3x3 refresh (radius=1).");
                    // performRefresh(player, 1); (not yet)
                    this.refreshFullView(player);
                } else {
                    this.refreshFullView(player);
                }
                refreshCooldowns.put(playerUUID, currentTime + refreshCooldownMillis);
            }
        } else {
            debugLog("State NOT changed for " + player.getName() + ". Current hidden state: " + newStateIsHidden);
        }
    }

    /**
     * Starts a periodic task to validate and correct player states.
     * This helps mitigate event reliability issues in Folia.
     */
    private void startStateValidationTask() {
        long intervalTicks = stateValidationIntervalSeconds * 20L; // Convert seconds to ticks
        // Run validation task periodically to check for state inconsistencies
        if (foliaLib != null) {
            foliaLib.getScheduler().runTimer(() -> {
                try {
                    validatePlayerStates();
                } catch (Exception e) {
                    getLogger().warning("[YLevelHider] Error in state validation task: " + e.getMessage());
                    if (debugMode) {
                        e.printStackTrace();
                    }
                }
            }, intervalTicks, intervalTicks);
            stateValidationTask = null;
        } else {
            stateValidationTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                try {
                    validatePlayerStates();
                } catch (Exception e) {
                    getLogger().warning("[YLevelHider] Error in state validation task: " + e.getMessage());
                    if (debugMode) {
                        e.printStackTrace();
                    }
                }
            }, intervalTicks, intervalTicks); // Start after interval, repeat every interval
        }
        
        debugLog("State validation task started with " + stateValidationIntervalSeconds + " second interval for Folia compatibility.");
    }

    /**
     * Starts a task to check if players are looking at fake deepslate blocks.
     * When detected, uncovers the relevant chunks.
     */
    private void startChunkUncoverTask() {
        long intervalTicks = lookCheckIntervalTicks;
        if (foliaLib != null) {
            foliaLib.getScheduler().runTimer(() -> {
                try {
                    checkPlayerLookingAtFakeBlocks();
                } catch (Exception e) {
                    getLogger().warning("[YLevelHider] Error in chunk uncover task: " + e.getMessage());
                    if (debugMode) {
                        e.printStackTrace();
                    }
                }
            }, intervalTicks, intervalTicks);
            chunkUncoverTask = null;
        } else {
            chunkUncoverTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                try {
                    checkPlayerLookingAtFakeBlocks();
                } catch (Exception e) {
                    getLogger().warning("[YLevelHider] Error in chunk uncover task: " + e.getMessage());
                    if (debugMode) {
                        e.printStackTrace();
                    }
                }
            }, intervalTicks, intervalTicks);
        }
        
        debugLog("Chunk uncovering task started with " + lookCheckIntervalTicks + " tick intervals (" + String.format("%.1f", lookCheckIntervalTicks / 20.0) + " seconds).");
    }

    /**
     * Validates that all online players have correct hidden states.
     * Corrects any inconsistencies found.
     */
    private void validatePlayerStates() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isWorldWhitelisted(player.getWorld().getName())) {
                // Player in non-whitelisted world should not have hidden state
                if (playerHiddenState.remove(player.getUniqueId()) != null) {
                    debugLog("State validation: Corrected " + player.getName() + " in non-whitelisted world " + player.getWorld().getName());
                    refreshFullView(player);
                }
                continue;
            }

            double currentY = player.getLocation().getY();
            boolean expectedHiddenState = currentY >= 31.0;
            Boolean currentHiddenState = playerHiddenState.get(player.getUniqueId());

            if (currentHiddenState == null || currentHiddenState != expectedHiddenState) {
                debugLog("State validation: Correcting state for " + player.getName() + 
                        " Y=" + String.format("%.2f", currentY) + 
                        " Expected=" + expectedHiddenState + 
                        " Current=" + currentHiddenState);
                        
                playerHiddenState.put(player.getUniqueId(), expectedHiddenState);
                
                // Only refresh if cooldown has expired to avoid spam
                UUID playerUUID = player.getUniqueId();
                long currentTime = System.currentTimeMillis();
                long expirationTime = refreshCooldowns.getOrDefault(playerUUID, 0L);
                
                if (currentTime >= expirationTime) {
                    refreshFullView(player);
                    refreshCooldowns.put(playerUUID, currentTime + refreshCooldownMillis);
                }
            }
        }
    }

    /**
     * Checks if players are looking at fake deepslate blocks and uncovers chunks if needed.
     * Uses simple raycasting to detect what block the player is looking at.
     */
    private void checkPlayerLookingAtFakeBlocks() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isWorldWhitelisted(player.getWorld().getName())) {
                continue;
            }
            
            UUID playerUUID = player.getUniqueId();
            boolean isHidden = playerHiddenState.getOrDefault(playerUUID, false);
            
            if (!isHidden) {
                continue; // Player not in hidden state, no need to check
            }
            
            Set<String> fakeChunks = playerFakeChunks.get(playerUUID);
            if (fakeChunks == null || fakeChunks.isEmpty()) {
                continue; // No fake chunks to check
            }
            
            try {
                // Perform raycast to see what block the player is looking at
                org.bukkit.block.Block targetBlock = player.getTargetBlockExact(lookDetectionRange);
                
                // Check if looking at the configured fake block material
                if (targetBlock != null && targetBlock.getType() == org.bukkit.Material.valueOf(fakeBlockMaterial)) {
                    int chunkX = targetBlock.getX() >> 4;
                    int chunkZ = targetBlock.getZ() >> 4;
                    String chunkKey = chunkX + "," + chunkZ;
                    
                    // Check if this chunk contains fake blocks
                    if (fakeChunks.contains(chunkKey) && targetBlock.getY() <= 16) {
                        debugLog("Player " + player.getName() + " looking at fake " + fakeBlockMaterial + " at " + 
                                targetBlock.getX() + "," + targetBlock.getY() + "," + targetBlock.getZ() + 
                                " in chunk " + chunkKey + ". Uncovering chunk.");
                        
                        // Remove from fake chunks and refresh the chunk
                        fakeChunks.remove(chunkKey);
                        
                        // Refresh this specific chunk for the player
                        World world = player.getWorld();
                        world.refreshChunk(chunkX, chunkZ);
                    }
                }
            } catch (Exception e) {
                debugLog("Error checking player look direction for " + player.getName() + ": " + e.getMessage());
            }
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        World respawnWorld = event.getRespawnLocation().getWorld();
        
        if (respawnWorld == null) return;
        
        infoLog("PlayerRespawnEvent for " + player.getName() + " in world " + respawnWorld.getName());

        // Schedule state handling for next tick to ensure player is fully loaded
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                if (isWorldWhitelisted(respawnWorld.getName())) {
                    debugLog("Player " + player.getName() + " respawned in whitelisted world " + respawnWorld.getName() + ". Handling initial state.");
                    handlePlayerInitialState(player, true);
                } else {
                    boolean wasHidden = playerHiddenState.remove(player.getUniqueId()) != null;
                    if (wasHidden) {
                        debugLog("Player " + player.getName() + " respawned in non-whitelisted world " + respawnWorld.getName() + ". State cleared, refreshing.");
                        refreshFullView(player);
                    }
                }
            }
        });
    }

    public WrappedBlockState getAirState() {
        return airState;
    }

    public int getAirStateGlobalId() {
        return airStateGlobalId;
    }

    public WrappedBlockState getDeepslateState() {
        return deepslateState;
    }

    public int getDeepslateStateGlobalId() {
        return deepslateStateGlobalId;
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
        // Early return if plugin is being disabled to prevent classloader issues
        if (plugin.pluginDisabling) {
            return;
        }
        
        try {
            // Safely get packet type name to avoid classloader issues
            String packetTypeName;
            try {
                packetTypeName = event.getPacketType().getName();
            } catch (Throwable t) {
                // Fallback if classloader is closed or other issues occur
                packetTypeName = "UNKNOWN_PACKET_TYPE";
            }
            
            listenerDebugLog("onPacketSend CALLED. PacketType: " + packetTypeName);

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
                listenerDebugLog("Bukkit.getPlayer(uuid) returned null or player offline for packet type: " + packetTypeName);
                return;
            }

            // Add a null-check for the player's world to prevent errors during world change/login.
            World playerWorld = player.getWorld();
            if (playerWorld == null || !plugin.isWorldWhitelisted(playerWorld.getName())) {
                return;
            }

            listenerDebugLog("Processing packet for " + player.getName() + " in whitelisted world " + player.getWorld().getName() + ". PacketType: " + packetTypeName);

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
            // Handle ENTITY_SPAWN (for non-living entities like armor stands)
            else if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
                handleEntitySpawnPacket(event, player);
            }
            // Handle SPAWN_LIVING_ENTITY
            else if (event.getPacketType() == PacketType.Play.Server.SPAWN_LIVING_ENTITY) {
                handleLivingEntitySpawnPacket(event, player);
            }
        } catch (Throwable t) {
            // Catch any unexpected errors to prevent them from bubbling up to PacketEvents
            // This prevents the zip file closed error and other classloader issues
            try {
                plugin.getLogger().warning("[YLevelHider][PacketListener] Caught unexpected error in onPacketSend: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                if (plugin.isDebugMode()) {
                    t.printStackTrace();
                }
            } catch (Throwable logError) {
                // If even logging fails due to classloader issues, try the most basic logging
                try {
                    plugin.getLogger().warning("[YLevelHider][PacketListener] Caught unexpected error in onPacketSend (error details unavailable due to classloader issues)");
                } catch (Throwable finalError) {
                    // Last resort - do nothing to avoid infinite error loops
                    // The plugin is likely being unloaded/reloaded
                }
            }
        }
    }

    private void handleChunkDataPacket(PacketSendEvent event, Player player) {
        listenerDebugLog("Intercepted CHUNK_DATA packet for " + player.getName());
        boolean shouldHide = plugin.playerHiddenState.getOrDefault(player.getUniqueId(), false);
        listenerDebugLog("Player: " + player.getName() + ", shouldHide: " + shouldHide + " (from playerHiddenState: " + plugin.playerHiddenState.get(player.getUniqueId()) + ")");

        if (shouldHide) {
            WrappedBlockState deepslate = plugin.getDeepslateState();
            if (deepslate == null) {
                plugin.getLogger().warning("[YLevelHider][PacketListener] " + plugin.fakeBlockMaterial + " block state is not available. Cannot modify chunk for " + player.getName());
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
            
            // Track chunk coordinates for fake deepslate
            String chunkKey = column.getX() + "," + column.getZ();
            UUID playerUUID = player.getUniqueId();

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
                                    if (currentState != null && !currentState.equals(deepslate)) {
                                        listenerDebugLog("CHUNK_DATA: Changing block at [" + relX + "," + yInSection + "," + relZ + "] in section " + sectionIndex +
                                                " (world Y " + currentWorldY + ") from " + currentState.getType().getName() + " to " + plugin.fakeBlockMaterial + " for player " + player.getName());
                                        section.set(relX, yInSection, relZ, deepslate);
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
                
                // Track this chunk as having fake deepslate blocks
                plugin.playerFakeChunks.computeIfAbsent(playerUUID, k -> ConcurrentHashMap.newKeySet()).add(chunkKey);
                
                event.markForReEncode(true);
                listenerDebugLog("CHUNK_DATA for " + player.getName() + " was modified to hide blocks at Y<=16 with " + plugin.fakeBlockMaterial + " and marked for re-encode.");
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
            WrappedBlockState deepslate = plugin.getDeepslateState();
            if (deepslate == null) return;

            WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
            Vector3i blockPos = wrapper.getBlockPosition();

            if (blockPos != null && blockPos.getY() <= 16) {
                WrappedBlockState currentState = wrapper.getBlockState();
                if (currentState != null && !currentState.equals(deepslate)) {
                    listenerDebugLog("BLOCK_CHANGE: Changing block at " + blockPos.toString() + " from " + currentState.getType().getName() + " to " + plugin.fakeBlockMaterial + " for " + player.getName());
                    wrapper.setBlockState(deepslate);
                    event.markForReEncode(true);
                }
            }
        }
    }

    private void handleMultiBlockChangePacket(PacketSendEvent event, Player player) {
        listenerDebugLog("Intercepted MULTI_BLOCK_CHANGE packet for " + player.getName());
        boolean shouldHide = plugin.playerHiddenState.getOrDefault(player.getUniqueId(), false);
        if (shouldHide) {
            WrappedBlockState deepslate = plugin.getDeepslateState();
            if (deepslate == null) return;

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
                    int deepslateId = plugin.getDeepslateStateGlobalId();
                    if (currentBlockId != deepslateId) {
                        listenerDebugLog("MULTI_BLOCK_CHANGE: Changing block at global ("+record.getX()+","+currentWorldY+","+record.getZ()+") from ID " + currentBlockId + " to " + plugin.fakeBlockMaterial + " for " + player.getName());
                        try {
                            record.setBlockId(deepslateId);
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

    private void handleEntitySpawnPacket(PacketSendEvent event, Player player) {
        listenerDebugLog("Intercepted SPAWN_ENTITY packet for " + player.getName());
        boolean shouldHide = plugin.playerHiddenState.getOrDefault(player.getUniqueId(), false);
        if (shouldHide && plugin.hideEntitiesBelowY30) {
            try {
                WrapperPlayServerSpawnEntity wrapper = new WrapperPlayServerSpawnEntity(event);
                Vector3d entityPos = wrapper.getPosition();
                
                if (entityPos != null && entityPos.getY() <= 30.0) {
                    listenerDebugLog("SPAWN_ENTITY: Cancelling entity spawn at Y=" + String.format("%.2f", entityPos.getY()) + " for " + player.getName());
                    event.setCancelled(true);
                }
            } catch (Exception e) {
                listenerDebugLog("Error handling SPAWN_ENTITY packet: " + e.getMessage());
            }
        }
    }

    private void handleLivingEntitySpawnPacket(PacketSendEvent event, Player player) {
        listenerDebugLog("Intercepted SPAWN_LIVING_ENTITY packet for " + player.getName());
        boolean shouldHide = plugin.playerHiddenState.getOrDefault(player.getUniqueId(), false);
        if (shouldHide && plugin.hideEntitiesBelowY30) {
            try {
                WrapperPlayServerSpawnLivingEntity wrapper = new WrapperPlayServerSpawnLivingEntity(event);
                Vector3d entityPos = wrapper.getPosition();
                
                if (entityPos != null && entityPos.getY() <= 30.0) {
                    listenerDebugLog("SPAWN_LIVING_ENTITY: Cancelling living entity spawn at Y=" + String.format("%.2f", entityPos.getY()) + " for " + player.getName());
                    event.setCancelled(true);
                }
            } catch (Exception e) {
                listenerDebugLog("Error handling SPAWN_LIVING_ENTITY packet: " + e.getMessage());
            }
        }
    }


    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // This method is required by the PacketListener interface.
    }
}
