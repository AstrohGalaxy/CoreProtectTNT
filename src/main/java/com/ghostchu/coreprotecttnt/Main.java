package com.ghostchu.coreprotecttnt;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Main extends JavaPlugin implements Listener {
    private final Cache<Object, String> probablyCache = CacheBuilder
            .newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .concurrencyLevel(4) // Sync and Async threads
            .maximumSize(50000) // Drop objects if too much, because it will cost expensive lookup.
            .recordStats()
            .build();
    private CoreProtectAPI api;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        Plugin depend = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (depend == null) {
            getPluginLoader().disablePlugin(this);
            return;
        }
        api = ((CoreProtect) depend).getAPI();
    }

    // Bed/RespawnAnchor explosion (tracing)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerInteractBedOrRespawnAnchorExplosion(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clickedBlock = e.getClickedBlock();
        Location locationHead = clickedBlock.getLocation();
        if (clickedBlock.getBlockData() instanceof Bed bed) {
            Location locationFoot = locationHead.clone().subtract(bed.getFacing().getDirection());
            if (bed.getPart() == Bed.Part.FOOT) {
                locationHead.add(bed.getFacing().getDirection());
            }
            String reason = "#bed-" + e.getPlayer().getName();
            probablyCache.put(locationHead, reason);
            probablyCache.put(locationFoot, reason);
        }
        if (clickedBlock.getBlockData() instanceof RespawnAnchor) {
            probablyCache.put(clickedBlock.getLocation(), "#respawnanchor-" + e.getPlayer().getName());
        }
    }

    // Creeper ignite (tracing)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerInteractCreeper(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Creeper)) {
            return;
        }
        probablyCache.put(e.getRightClicked(), "#ignitecreeper-" + e.getPlayer().getName());
    }

    // Block explode (logger)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockExplode(BlockExplodeEvent e) {
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "block-explosion");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        Location location = e.getBlock().getLocation();
        String probablyCauses = probablyCache.getIfPresent(e.getBlock());
        if (probablyCauses == null) {
            probablyCauses = probablyCache.getIfPresent(location);
        }
        if (probablyCauses == null) {
            if (section.getBoolean("disable-unknown", true)) {
                e.blockList().clear();
                Util.broadcastNearPlayers(location, section.getString("alert"));
            }
            return;
        }
        // Found causes, let's begin logging
        for (Block block : e.blockList()) {
            api.logRemoval(probablyCauses, block.getLocation(), block.getType(), block.getBlockData());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockPlaceOnHanging(BlockPlaceEvent event) {
        // We can't check the hanging in this event, may cause server lagging, just store it
        probablyCache.put(event.getBlock().getLocation(), event.getPlayer().getName());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        // We can't check the hanging in this event, may cause server lagging, just store it
        // Maybe a player break the tnt and a plugin igniting it?
        probablyCache.put(event.getBlock().getLocation(), event.getPlayer().getName());
    }

    // Player item put into ItemFrame / Rotate ItemFrame (logger)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onClickItemFrame(PlayerInteractEntityEvent e) { // Add item to item-frame or rotating
        if (!(e.getRightClicked() instanceof ItemFrame itemFrame)) {
            return;
        }
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "itemframe");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        // Player interacted itemframe
        api.logInteraction(e.getPlayer().getName(), e.getRightClicked().getLocation());
        // Check item I/O
        if (itemFrame.getItem().getType().isAir()) { // Probably put item now
            ItemStack mainItem = e.getPlayer().getInventory().getItemInMainHand();
            ItemStack offItem = e.getPlayer().getInventory().getItemInOffHand();
            ItemStack putIn = mainItem.getType().isAir() ? offItem : mainItem;
            if (!putIn.getType().isAir()) {
                // Put in item
                api.logPlacement("#additem-" + e.getPlayer().getName(), e.getRightClicked().getLocation(), putIn.getType(), null);
                return;
            }
        }
        // Probably rotating ItemFrame
        api.logRemoval("#rotate-" + e.getPlayer().getName(), e.getRightClicked().getLocation(), itemFrame.getItem().getType(), null);
        api.logPlacement("#rotate-" + e.getPlayer().getName(), e.getRightClicked().getLocation(), itemFrame.getItem().getType(), null);
    }

    // Any projectile shoot (listener)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onProjectileLaunch(ProjectileLaunchEvent e) {
        ProjectileSource projectileSource = e.getEntity().getShooter();
        if (projectileSource == null) {
            return;
        }
        
        StringBuilder source = new StringBuilder();
        if (!(projectileSource instanceof Player)) {
            source.append("#"); // We only hope non-player object use hashtag
        }
        source.append(e.getEntity().getName()).append("-");
        
        if (projectileSource instanceof Entity entity) {
            if (projectileSource instanceof Mob mob && mob.getTarget() != null) {
                source.append(mob.getTarget().getName());
            } else {
                source.append(entity.getName());
            }
        } else if (projectileSource instanceof Block block) {
            source.append(block.getType().name());
        } else {
            source.append(projectileSource.getClass().getSimpleName());
        }
        
        String sourceStr = source.toString();
        probablyCache.put(e.getEntity(), sourceStr);
        probablyCache.put(projectileSource, sourceStr);
    }

    // TNT ignites by Player (listener)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onIgniteTNT(EntitySpawnEvent e) {
        Entity tnt = e.getEntity();
        if (!(tnt instanceof TNTPrimed tntPrimed)) {
            return;
        }
        Entity source = tntPrimed.getSource();
        if (source != null) {
            // Bukkit has given the ignition source, track it directly.
            String sourceFromCache = probablyCache.getIfPresent(source);
            if (sourceFromCache != null) {
                probablyCache.put(tnt, sourceFromCache);
                return;
            }
            if (source.getType() == EntityType.PLAYER) {
                probablyCache.put(tntPrimed, source.getName());
                return;
            }
        }
        // Check nearby locations for the TNT source
        Location blockCorner = tnt.getLocation().clone().subtract(0.5, 0, 0.5);
        for (Map.Entry<Object, String> entry : probablyCache.asMap().entrySet()) {
            if (entry.getKey() instanceof Location loc) {
                if (loc.getWorld().equals(blockCorner.getWorld()) && loc.distance(blockCorner) < 0.5) {
                    probablyCache.put(tnt, entry.getValue());
                    return;
                }
            }
        }
    }

    // HangingBreak (logger)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHangingBreak(HangingBreakEvent e) {
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "hanging");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        if (e.getCause() == HangingBreakEvent.RemoveCause.PHYSICS || e.getCause() == HangingBreakEvent.RemoveCause.DEFAULT) {
            return; // We can't track them tho.
        }

        Block hangingPosBlock = e.getEntity().getLocation().getBlock();
        String reason = probablyCache.getIfPresent(hangingPosBlock.getLocation());
        if (reason != null) {
            Material mat = Material.matchMaterial(e.getEntity().getType().name());
            if (mat != null) {
                api.logRemoval("#" + e.getCause().name() + "-" + reason, hangingPosBlock.getLocation(), Material.matchMaterial(e.getEntity().getType().name()), null);
            } else {
                api.logInteraction("#" + e.getCause().name() + "-" + reason, hangingPosBlock.getLocation());
            }
        }
    }

    // EndCrystal rigged by entity (listener)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onEndCrystalHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof EnderCrystal)) {
            return;
        }
        if (e.getDamager() instanceof Player player) {
            probablyCache.put(e.getEntity(), player.getName());
            return;
        }
        String sourceFromCache = probablyCache.getIfPresent(e.getDamager());
        if (sourceFromCache != null) {
            probablyCache.put(e.getEntity(), sourceFromCache);
        } else if (e.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            probablyCache.put(e.getEntity(), player.getName());
        }
    }

    // Hanging hit by entity (logger)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHangingHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Hanging)) {
            return;
        }
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "itemframe");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        if (!(e.getEntity() instanceof ItemFrame itemFrame)) {
            return;
        }
        if (itemFrame.getItem().getType().isAir() || itemFrame.isInvulnerable()) {
            return;
        }
        if (e.getDamager() instanceof Player player) {
            probablyCache.put(e.getEntity(), player.getName());
            api.logInteraction(player.getName(), itemFrame.getLocation());
            api.logRemoval(player.getName(), itemFrame.getLocation(), itemFrame.getItem().getType(), null);
        } else {
            String cause = probablyCache.getIfPresent(e.getDamager());
            if (cause != null) {
                String reason = "#" + e.getDamager().getName() + "-" + cause;
                probablyCache.put(e.getEntity(), reason);
                api.logRemoval(reason, itemFrame.getLocation(), itemFrame.getItem().getType(), null);
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPaintingHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Painting)) {
            return;
        }
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "painting");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        Painting painting = (Painting) e.getEntity();
        if (painting.isInvulnerable()) {
            return;
        }

        if (e.getDamager() instanceof Player player) {
            api.logInteraction(player.getName(), painting.getLocation());
        } else {
            String reason = probablyCache.getIfPresent(e.getDamager());
            if (reason != null) {
                api.logInteraction("#" + e.getDamager().getName() + "-" + reason, painting.getLocation());
            } else {
                if (section.getBoolean("disable-unknown")) {
                    e.setCancelled(true);
                    e.setDamage(0.0d);
                    Util.broadcastNearPlayers(e.getEntity().getLocation(), section.getString("alert"));
                }
            }
        }
    }


    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onEntityHitByProjectile(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Projectile projectile)) {
            return;
        }
        if (projectile.getShooter() instanceof Player player) {
            probablyCache.put(e.getEntity(), player.getName());
            return;
        }
        String reason = probablyCache.getIfPresent(e.getDamager());
        if (reason != null) {
            probablyCache.put(e.getEntity(), reason);
        } else {
            probablyCache.put(e.getEntity(), e.getDamager().getName());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockIgnite(BlockIgniteEvent e) {
        Location blockLocation = e.getBlock().getLocation();
        
        if (e.getIgnitingEntity() != null) {
            if (e.getIgnitingEntity().getType() == EntityType.PLAYER) {
                probablyCache.put(blockLocation, e.getPlayer().getName());
                // Don't add it to probablyIgnitedThisTick because it's the simplest case and is logged by Core Protect
                return;
            }
            String sourceFromCache = probablyCache.getIfPresent(e.getIgnitingEntity());
            if (sourceFromCache != null) {
                probablyCache.put(blockLocation, sourceFromCache);
                return;
            }
            if (e.getIgnitingEntity() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
                probablyCache.put(blockLocation, player.getName());
                return;
            }
        }
        
        if (e.getIgnitingBlock() != null) {
            String sourceFromCache = probablyCache.getIfPresent(e.getIgnitingBlock().getLocation());
            if (sourceFromCache != null) {
                probablyCache.put(blockLocation, sourceFromCache);
                return;
            }
        }
        
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "fire");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        if (section.getBoolean("disable-unknown", true)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBurn(BlockBurnEvent e) {
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "fire");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        if (e.getIgnitingBlock() != null) {
            String sourceFromCache = probablyCache.getIfPresent(e.getIgnitingBlock().getLocation());
            if (sourceFromCache != null) {
                probablyCache.put(e.getBlock().getLocation(), sourceFromCache);
                api.logRemoval("#fire-" + sourceFromCache, e.getBlock().getLocation(), e.getBlock().getType(), e.getBlock().getBlockData());
            } else if (section.getBoolean("disable-unknown", true)) {
                e.setCancelled(true);
                Util.broadcastNearPlayers(e.getIgnitingBlock().getLocation(), section.getString("alert"));
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onBombHit(ProjectileHitEvent e) {
        if (!(e.getHitEntity() instanceof ExplosiveMinecart || e.getHitEntity() instanceof EnderCrystal)) {
            return;
        }
        if (e.getEntity().getShooter() instanceof Player shooter) {
            String sourceFromCache = probablyCache.getIfPresent(e.getEntity());
            if (sourceFromCache != null) {
                probablyCache.put(e.getHitEntity(), sourceFromCache);
            } else {
                probablyCache.put(e.getHitEntity(), shooter.getName());
            }
        }
    }


    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onExplode(EntityExplodeEvent e) {
        Entity entity = e.getEntity();
        List<Block> blockList = e.blockList();
        if (blockList.isEmpty()) {
            return;
        }
        
        ConfigurationSection section = Util.bakeConfigSection(getConfig(), "entity-explosion");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        
        String track = probablyCache.getIfPresent(entity);
        String entityName = e.getEntityType().name().toLowerCase(Locale.ROOT);
        boolean disableUnknown = section.getBoolean("disable-unknown", true);
        
        // TNT or EnderCrystal
        if (entity instanceof TNTPrimed || entity instanceof EnderCrystal) {
            if (track != null) {
                String reason = "#" + entityName + "-" + track;
                logBlocksAndCache(blockList, reason);
                probablyCache.invalidate(entity);
            } else if (disableUnknown) {
                blockList.clear();
                entity.remove();
                Util.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
            }
            return;
        }
        
        // Creeper
        if (entity instanceof Creeper creeper) {
            if (track != null) {
                logBlocksAndCache(blockList, track);
            } else {
                LivingEntity creeperTarget = creeper.getTarget();
                if (creeperTarget != null) {
                    String reason = "#creeper-" + creeperTarget.getName();
                    logBlocksAndCache(blockList, reason);
                } else if (disableUnknown) {
                    blockList.clear();
                    entity.remove();
                    Util.broadcastNearPlayers(e.getLocation(), section.getString("alert"));
                }
            }
            return;
        }
        
        // Fireball
        if (entity instanceof Fireball) {
            if (track != null) {
                String reason = "#fireball-" + track;
                logBlocksAndCache(blockList, reason);
                probablyCache.invalidate(entity);
            } else if (disableUnknown) {
                blockList.clear();
                entity.remove();
                Util.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
            }
            return;
        }
        
        // ExplosiveMinecart
        if (entity instanceof ExplosiveMinecart) {
            boolean isLogged = false;
            Location blockCorner = entity.getLocation().clone().subtract(0.5, 0, 0.5);
            for (Map.Entry<Object, String> entry : probablyCache.asMap().entrySet()) {
                if (entry.getKey() instanceof Location loc) {
                    if (loc.getWorld().equals(blockCorner.getWorld()) && loc.distance(blockCorner) < 1) {
                        String reason = "#tntminecart-" + entry.getValue();
                        logBlocksAndCache(blockList, reason);
                        isLogged = true;
                        break;
                    }
                }
            }
            if (!isLogged) {
                if (track != null) {
                    String reason = "#tntminecart-" + track;
                    logBlocksAndCache(blockList, reason);
                    probablyCache.invalidate(entity);
                } else if (disableUnknown) {
                    blockList.clear();
                    Util.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
                }
            }
            return;
        }
        
        // Other entities
        if (track == null || track.isEmpty()) {
            if (entity instanceof Mob mob && mob.getTarget() != null) {
                track = mob.getTarget().getName();
            }
        }
        
        // Try to get track from last damage cause if still null
        if (track == null || track.isEmpty()) {
            EntityDamageEvent cause = entity.getLastDamageCause();
            if (cause instanceof EntityDamageByEntityEvent entityDamageByEntityEvent) {
                track = "#" + entity.getName() + "-" + entityDamageByEntityEvent.getDamager().getName();
            }
        }

        if (track != null && !track.isEmpty()) {
            for (Block block : blockList) {
                api.logRemoval(track, block.getLocation(), block.getType(), block.getBlockData());
            }
        } else if (disableUnknown) {
            blockList.clear();
            entity.remove();
            Util.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
        }
    }
    
    private void logBlocksAndCache(List<Block> blocks, String reason) {
        for (Block block : blocks) {
            api.logRemoval(reason, block.getLocation(), block.getType(), block.getBlockData());
            probablyCache.put(block.getLocation(), reason);
        }
    }
}
