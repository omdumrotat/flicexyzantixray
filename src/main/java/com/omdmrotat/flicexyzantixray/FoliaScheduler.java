package com.omdmrotat.flicexyzantixray;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Utility class to handle both Paper and Folia scheduling.
 * This provides compatibility layer for regionized threading in Folia
 * while maintaining compatibility with Paper.
 */
public class FoliaScheduler {
    
    private static final boolean IS_FOLIA;
    
    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            folia = false;
        }
        IS_FOLIA = folia;
    }
    
    /**
     * Check if the server is running Folia
     * @return true if Folia, false if Paper/Spigot
     */
    public static boolean isFolia() {
        return IS_FOLIA;
    }
    
    /**
     * Schedule a task to run synchronously.
     * On Folia, this will run in the appropriate region.
     * On Paper, this will run on the main thread.
     * 
     * @param plugin the plugin instance
     * @param task the task to run
     */
    public static void runTask(JavaPlugin plugin, Runnable task) {
        if (IS_FOLIA) {
            runFoliaGlobalTask(plugin, task);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
    
    /**
     * Schedule a task to run synchronously for a specific player.
     * On Folia, this will run in the player's region.
     * On Paper, this will run on the main thread.
     * 
     * @param plugin the plugin instance
     * @param player the player whose region the task should run in
     * @param task the task to run
     */
    public static void runTask(JavaPlugin plugin, Player player, Runnable task) {
        if (IS_FOLIA) {
            runFoliaEntityTask(plugin, player, task);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
    
    /**
     * Run a task in Folia's global region (for operations not tied to a specific region)
     */
    private static void runFoliaGlobalTask(JavaPlugin plugin, Runnable task) {
        try {
            // Use reflection to call Folia's global region scheduler
            Class<?> foliaGlobalRegionSchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.FoliaGlobalRegionScheduler");
            Object globalRegionScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            
            globalRegionSchedulerClass.getMethod("run", JavaPlugin.class, Runnable.class)
                .invoke(globalRegionScheduler, plugin, task);
        } catch (Exception e) {
            // Fallback to regular scheduler if reflection fails
            plugin.getLogger().warning("Failed to use Folia global scheduler, falling back to Bukkit scheduler: " + e.getMessage());
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
    
    /**
     * Run a task in the region of a specific entity (player)
     */
    private static void runFoliaEntityTask(JavaPlugin plugin, Player player, Runnable task) {
        try {
            // Use reflection to call Folia's entity scheduler
            Object entityScheduler = player.getClass().getMethod("getScheduler").invoke(player);
            entityScheduler.getClass().getMethod("run", JavaPlugin.class, Runnable.class, Runnable.class)
                .invoke(entityScheduler, plugin, task, null);
        } catch (Exception e) {
            // Fallback to regular scheduler if reflection fails
            plugin.getLogger().warning("Failed to use Folia entity scheduler for player " + player.getName() + ", falling back to Bukkit scheduler: " + e.getMessage());
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}