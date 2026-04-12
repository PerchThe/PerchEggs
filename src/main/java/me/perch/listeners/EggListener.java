package me.perch.listeners;

import me.perch.Eggs;
import me.perch.manager.ConfigManager;
import me.perch.manager.EggManager;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SpawnEggMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class EggListener implements Listener {

    private final Eggs plugin;
    private final Set<UUID> caughtPlayers = new HashSet<>();
    private final Set<UUID> entitiesBeingCaught = new HashSet<>();
    private final Set<UUID> processedEggs = new HashSet<>();

    private boolean spawningCustomEgg = false;

    public EggListener(Eggs plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMobSpawn(CreatureSpawnEvent event) {
        if (spawningCustomEgg) {
            event.setCancelled(false);
        }
    }

    @EventHandler
    public void onEggHatchAttempt(PlayerEggThrowEvent event) {
        Egg egg = event.getEgg();
        EggManager em = plugin.getEggManager();
        if (egg.getPersistentDataContainer().has(em.getKeyProjectileUses(), PersistentDataType.INTEGER)) {
            event.setHatching(false);
            event.setNumHatches((byte) 0);
        }
    }

    @EventHandler
    public void onEntityCombust(EntityCombustEvent event) {
        if (entitiesBeingCaught.contains(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (entitiesBeingCaught.contains(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
            event.blockList().clear();
        }
    }

    @EventHandler
    public void onEntityTarget(EntityTargetEvent event) {
        if (entitiesBeingCaught.contains(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof org.bukkit.entity.Egg egg) {
            EggManager em = plugin.getEggManager();
            if (egg.getPersistentDataContainer().has(em.getKeyProjectileUses(), PersistentDataType.INTEGER)) {
                event.setCancelled(true);
                return;
            }
        }
        if (entitiesBeingCaught.contains(event.getDamager().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        if (caughtPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            String blockName = event.getClickedBlock().getType().name();
            if (blockName.contains("SIGN")) {
                return;
            }
        }

        if (event.isCancelled() || event.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
            return;
        }

        ItemStack item = event.getItem();
        EggManager em = plugin.getEggManager();

        if (item == null || !em.isPerchEgg(item)) return;

        if (item.getType() != plugin.getConfigManager().getEggMaterial()) {
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
                handleFilledEggPlacement(event, player, item, em);
            }
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && !player.isSneaking()) {
            if (event.getClickedBlock().getState() instanceof InventoryHolder) return;
        }

        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.setCancelled(true);

        if (!player.hasPermission("percheggs.use")) {
            player.sendMessage(plugin.getConfigManager().getMessage("messages.no-permission"));
            return;
        }

        int uses = 1;
        if (item.getItemMeta().getPersistentDataContainer().has(em.getKeyUses(), PersistentDataType.INTEGER)) {
            uses = item.getItemMeta().getPersistentDataContainer().get(em.getKeyUses(), PersistentDataType.INTEGER);
        }

        org.bukkit.entity.Egg eggEntity = player.launchProjectile(org.bukkit.entity.Egg.class);
        eggEntity.getPersistentDataContainer().set(em.getKeyProjectileUses(), PersistentDataType.INTEGER, uses);

        player.playSound(player.getLocation(), Sound.ENTITY_EGG_THROW, 0.5f, 1.2f);

        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Egg egg)) return;

        EggManager em = plugin.getEggManager();
        if (!egg.getPersistentDataContainer().has(em.getKeyProjectileUses(), PersistentDataType.INTEGER)) return;

        if (processedEggs.contains(egg.getUniqueId())) return;
        processedEggs.add(egg.getUniqueId());

        new BukkitRunnable() {
            @Override public void run() { processedEggs.remove(egg.getUniqueId()); }
        }.runTaskLater(plugin, 100L);

        int uses = egg.getPersistentDataContainer().get(em.getKeyProjectileUses(), PersistentDataType.INTEGER);
        Player shooter = (egg.getShooter() instanceof Player) ? (Player) egg.getShooter() : null;

        boolean catchStarted = false;

        if (!event.isCancelled() && event.getHitEntity() instanceof LivingEntity target && !(target instanceof Player) && shooter != null) {
            catchStarted = attemptCatchSequence(shooter, target, uses, null, em, plugin.getConfigManager());
        }

        if (catchStarted) {
            egg.remove();
            return;
        }

        Location dropLoc = egg.getLocation();
        boolean gpRefundExpected = false;

        if (shooter != null) {
            if (isPermissionDenied(shooter, dropLoc)) {
                gpRefundExpected = true;
            }

            if (shooter.getGameMode() != org.bukkit.GameMode.CREATIVE) {
                ItemStack refundItem = em.createPerchEgg(uses);
                giveOrDrop(shooter, refundItem);
                shooter.playSound(shooter.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.5f);

                if (gpRefundExpected) {
                    removeOneVanillaEgg(shooter);
                }
            }
        } else {
            ItemStack refundItem = em.createPerchEgg(uses);
            dropLoc.getWorld().dropItem(dropLoc, refundItem);
        }

        egg.remove();
    }

    private void removeOneVanillaEgg(Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                Inventory inv = player.getInventory();
                for (int i = 0; i < inv.getSize(); i++) {
                    ItemStack item = inv.getItem(i);
                    if (item != null && item.getType() == Material.EGG) {
                        if (!plugin.getEggManager().isPerchEgg(item)) {
                            item.setAmount(item.getAmount() - 1);
                            break;
                        }
                    }
                }
            }
        }.runTaskLater(plugin, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMobClickCatch(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        Entity target = event.getRightClicked();

        if (caughtPlayers.contains(player.getUniqueId()) || entitiesBeingCaught.contains(target.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        ItemStack handItem = player.getInventory().getItemInMainHand();
        EggManager em = plugin.getEggManager();
        ConfigManager cm = plugin.getConfigManager();

        if (!em.isPerchEgg(handItem)) return;
        if (handItem.getType() != cm.getEggMaterial()) return;

        event.setCancelled(true);

        if (!(target instanceof LivingEntity livingTarget) || target instanceof Player) return;

        int uses = 1;
        if (handItem.getItemMeta().getPersistentDataContainer().has(em.getKeyUses(), PersistentDataType.INTEGER)) {
            uses = handItem.getItemMeta().getPersistentDataContainer().get(em.getKeyUses(), PersistentDataType.INTEGER);
        }

        boolean success = attemptCatchSequence(player, livingTarget, uses, handItem, em, cm);

        if (success) {
            if (uses <= 1) {
                if (handItem.getAmount() > 1) {
                    handItem.setAmount(handItem.getAmount() - 1);
                } else {
                    player.getInventory().setItemInMainHand(null);
                }
            } else {
                int newUses = uses - 1;
                ItemMeta meta = handItem.getItemMeta();
                meta.getPersistentDataContainer().set(em.getKeyUses(), PersistentDataType.INTEGER, newUses);
                em.updateEggVisuals(meta, newUses);
                handItem.setItemMeta(meta);
            }
        }
    }

    private void handleFilledEggPlacement(PlayerInteractEvent event, Player player, ItemStack item, EggManager em) {
        event.setCancelled(true);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);

        Block block = event.getClickedBlock();
        if (isPermissionDenied(player, block.getLocation())) {
            player.sendMessage(plugin.getConfigManager().getMessage("messages.no-permission"));
            return;
        }

        BlockFace face = event.getBlockFace();
        Location spawnLoc = block.getRelative(face).getLocation().add(0.5, 0, 0.5);
        spawnLoc.setYaw(player.getLocation().getYaw() + 180);

        if (item.getItemMeta() instanceof SpawnEggMeta meta) {
            EntitySnapshot snapshot = meta.getSpawnedEntity();
            if (snapshot == null) return;

            spawningCustomEgg = true;
            Entity spawned = null;
            try {
                spawned = snapshot.createEntity(spawnLoc);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                spawningCustomEgg = false;
            }

            if (spawned == null) return;

            String currentNameSerialized = "";
            if (meta.displayName() != null) {
                currentNameSerialized = MiniMessage.miniMessage().serialize(meta.displayName());
            }

            String originalNameSerialized = meta.getPersistentDataContainer().get(em.getKeyOriginalName(), PersistentDataType.STRING);

            if (originalNameSerialized != null && !currentNameSerialized.equals(originalNameSerialized)) {
                spawned.customName(meta.displayName());
                spawned.setCustomNameVisible(true);
            }

            if (player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
                item.setAmount(item.getAmount() - 1);
            }

            if (spawned instanceof LivingEntity livingSpawned) {
                freezeEntity(livingSpawned, 30);
                playSpawnAnimation(spawnLoc, livingSpawned);
            } else {
                playSpawnAnimation(spawnLoc, null);
            }
        }
    }

    private boolean attemptCatchSequence(Player player, LivingEntity livingTarget, int uses, ItemStack handItem, EggManager em, ConfigManager cm) {
        if (caughtPlayers.contains(player.getUniqueId()) || entitiesBeingCaught.contains(livingTarget.getUniqueId())) return false;

        if (!player.hasPermission("percheggs.use")) {
            player.sendMessage(cm.getMessage("messages.no-permission"));
            return false;
        }

        if (isPermissionDenied(player, livingTarget.getLocation())) {
            player.sendMessage(cm.getMessage("messages.no-permission"));
            return false;
        }

        String typeName = livingTarget.getType().name();
        boolean isBlacklisted = cm.getBlacklist().stream().anyMatch(s -> s.equalsIgnoreCase(typeName));

        if (isBlacklisted) {
            player.sendMessage(cm.getMessage("messages.catch-fail-type"));
            return false;
        }

        if (livingTarget instanceof Tameable tameable && tameable.isTamed()) {
            if (tameable.getOwner() != null && !tameable.getOwner().getUniqueId().equals(player.getUniqueId())) {
                player.sendMessage(cm.getMessage("messages.catch-fail-type"));
                return false;
            }
        }

        ItemStack potentialEgg = em.createFilledSpawnEgg(livingTarget);
        if (potentialEgg == null) {
            player.sendMessage(cm.getMessage("messages.catch-fail-type"));
            return false;
        }

        caughtPlayers.add(player.getUniqueId());
        entitiesBeingCaught.add(livingTarget.getUniqueId());

        livingTarget.setInvulnerable(true);
        livingTarget.setFireTicks(0);
        if (livingTarget instanceof Mob mob) mob.setTarget(null);

        if (livingTarget instanceof Creeper creeper) {
            creeper.setIgnited(false);
            creeper.setFuseTicks(0);
        }

        freezeEntity(livingTarget, 60);

        new BukkitRunnable() {
            int ticks = 0;
            final int maxTicks = 60;

            @Override
            public void run() {
                if (!livingTarget.isValid() || !player.isOnline()) {

                    if (player.isOnline() && handItem == null) {
                        ItemStack refund = em.createPerchEgg(uses);
                        giveOrDrop(player, refund);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
                    }

                    cleanup();
                    this.cancel();
                    return;
                }

                Location mobLoc = livingTarget.getLocation();
                Location playerLoc = player.getLocation().add(0, 1.0, 0);
                double distSq = mobLoc.distanceSquared(playerLoc);

                if (distSq < 0.65 || ticks >= maxTicks) {
                    completeCatch(player, livingTarget, em, cm, potentialEgg, uses, handItem != null);
                    cleanup();
                    this.cancel();
                    return;
                }

                Vector direction = playerLoc.toVector().subtract(mobLoc.toVector()).normalize();
                double speed = 0.2 + (ticks * 0.015);
                if (speed > 0.6) speed = 0.6;

                livingTarget.setVelocity(direction.multiply(speed));

                player.getWorld().spawnParticle(Particle.END_ROD, mobLoc.add(0, 0.5, 0), 1, 0, 0, 0, 0);

                if (ticks % 5 == 0) {
                    float pitch = 1.0f + (ticks * 0.02f);
                    player.playSound(mobLoc, Sound.ENTITY_CHICKEN_EGG, 0.1f, pitch);
                }

                ticks++;
            }

            private void cleanup() {
                caughtPlayers.remove(player.getUniqueId());
                entitiesBeingCaught.remove(livingTarget.getUniqueId());
                if (livingTarget.isValid()) {
                    livingTarget.setInvulnerable(false);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        return true;
    }

    private void completeCatch(Player player, LivingEntity livingTarget, EggManager em, ConfigManager cm, ItemStack spawnEgg, int originalUses, boolean wasHandInteraction) {
        Location catchLocation = livingTarget.getLocation().add(0, 0.5, 0);
        livingTarget.remove();

        catchLocation.getWorld().spawnParticle(Particle.CLOUD, catchLocation, 10, 0.3, 0.3, 0.3, 0.1);
        catchLocation.getWorld().playSound(catchLocation, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.1f, 1.0f);

        giveOrDrop(player, spawnEgg);

        if (!wasHandInteraction && originalUses > 1) {
            ItemStack returnedEmpty = em.createPerchEgg(originalUses - 1);
            giveOrDrop(player, returnedEmpty);
        }
    }

    private boolean isPermissionDenied(Player player, Location loc) {
        if (Bukkit.getPluginManager().getPlugin("GriefPrevention") == null) return false;
        try {
            if (GriefPrevention.instance != null) {
                Claim claim = GriefPrevention.instance.dataStore.getClaimAt(loc, false, null);
                if (claim != null) {
                    String denyReason = claim.allowBuild(player, Material.AIR);
                    if (denyReason == null) denyReason = claim.allowContainers(player);
                    return denyReason != null;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void freezeEntity(LivingEntity entity, int ticks) {
        entity.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 255, false, false));
        entity.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, ticks, 250, false, false));
    }

    private void unfreezeEntity(LivingEntity entity) {
        entity.removePotionEffect(PotionEffectType.SLOWNESS);
        entity.removePotionEffect(PotionEffectType.JUMP_BOOST);
    }

    private void playSpawnAnimation(Location loc, LivingEntity entity) {
        new BukkitRunnable() {
            int ticks = 0;
            final int maxTicks = 10;
            @Override
            public void run() {
                if (ticks >= maxTicks) {
                    loc.getWorld().spawnParticle(Particle.CLOUD, loc, 10, 0.3, 0.1, 0.3, 0.05);
                    loc.getWorld().playSound(loc, Sound.ENTITY_CHICKEN_EGG, 0.1f, 0.5f);
                    if (entity != null && entity.isValid()) {
                        unfreezeEntity(entity);
                        entity.setVelocity(new Vector(0, -0.05, 0));
                    }
                    this.cancel();
                    return;
                }
                if (entity != null && entity.isValid()) {
                    Vector vel = entity.getVelocity();
                    entity.setVelocity(new Vector(0, vel.getY(), 0));
                }
                double height = 2.0 - (ticks * 0.2);
                double angle = ticks * 0.5;
                double x = Math.cos(angle) * 0.7;
                double z = Math.sin(angle) * 0.7;
                Location particleLoc = loc.clone().add(x, height, z);
                loc.getWorld().spawnParticle(Particle.END_ROD, particleLoc, 1, 0, 0, 0, 0);
                if (ticks % 2 == 0) {
                    float pitch = 2.0f - (ticks * 0.15f);
                    loc.getWorld().playSound(loc, Sound.ENTITY_CHICKEN_EGG, 0.1f, pitch);
                }
                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void giveOrDrop(Player player, ItemStack item) {
        if (player.getInventory().firstEmpty() != -1) {
            player.getInventory().addItem(item);
        } else {
            player.getWorld().dropItem(player.getLocation(), item);
            }
        }
    }