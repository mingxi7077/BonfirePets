package com.bonfire.pets.runtime;

import com.bonfire.pets.BonfirePets;
import com.bonfire.pets.adapter.BetterModelAdapter;
import com.bonfire.pets.adapter.MythicMobsRuntimeAdapter;
import com.bonfire.pets.model.PetDefinition;
import com.bonfire.pets.model.PlayerPetProfile;
import com.bonfire.pets.storage.BonfirePetsStorage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class PetRuntimeManager implements AutoCloseable {

    private static final long STARTUP_SUMMON_DELAY_TICKS = 20L;
    private static final long REMOUNT_RETRY_1 = 1L;
    private static final long REMOUNT_RETRY_2 = 5L;
    private static final long REMOUNT_RETRY_3 = 20L;

    private final BonfirePets plugin;
    private final BonfirePetsStorage storage;
    private final BetterModelAdapter betterModelAdapter;
    private final MythicMobsRuntimeAdapter mythicMobsRuntimeAdapter;
    private final ConcurrentMap<UUID, ActivePetSession> sessionsByOwner = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> ownerByPetEntity = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Boolean> forcedDismounts = new ConcurrentHashMap<>();
    private final NamespacedKey managedKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey petIdKey;

    private BonfirePetsListener listener;

    public PetRuntimeManager(BonfirePets plugin,
                             BonfirePetsStorage storage,
                             BetterModelAdapter betterModelAdapter,
                             MythicMobsRuntimeAdapter mythicMobsRuntimeAdapter) {
        this.plugin = plugin;
        this.storage = storage;
        this.betterModelAdapter = betterModelAdapter;
        this.mythicMobsRuntimeAdapter = mythicMobsRuntimeAdapter;
        this.managedKey = new NamespacedKey(plugin, "managed");
        this.ownerKey = new NamespacedKey(plugin, "owner");
        this.petIdKey = new NamespacedKey(plugin, "pet_id");
        this.betterModelAdapter.registerMountListener(this::handleMountEvent);
        this.betterModelAdapter.registerDismountListener(this::handleDismountEvent);
    }

    public void initialize() {
        listener = new BonfirePetsListener(this);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                Bukkit.getOnlinePlayers().forEach(player -> queueEnsureActivePet(player, "startup")), STARTUP_SUMMON_DELAY_TICKS);
    }

    BonfirePets plugin() {
        return plugin;
    }

    public void refreshOnlinePlayers(String reason) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            new ArrayList<>(sessionsByOwner.keySet()).forEach(ownerUuid -> dismissActivePet(ownerUuid, reason));
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    Bukkit.getOnlinePlayers().forEach(player -> queueEnsureActivePet(player, reason)), STARTUP_SUMMON_DELAY_TICKS);
        });
    }

    public void queueEnsureActivePet(Player player, String reason) {
        if (player == null) {
            return;
        }
        UUID ownerUuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<PlayerPetProfile> profile = storage.findPlayerProfile(ownerUuid);
                if (profile.isEmpty()) {
                    queueDismissActivePet(ownerUuid, reason + "-no-profile");
                    return;
                }
                PlayerPetProfile playerPetProfile = profile.get();
                String activePetId = playerPetProfile.activePetId();
                if (activePetId == null || activePetId.isBlank()) {
                    queueDismissActivePet(ownerUuid, reason + "-no-active");
                    return;
                }
                Optional<PetDefinition> pet = storage.findPetDefinition(activePetId);
                if (pet.isEmpty()) {
                    plugin.getLogger().warning("BonfirePets runtime skipped player " + ownerUuid + " because pet was not found: " + activePetId);
                    queueDismissActivePet(ownerUuid, reason + "-missing-pet");
                    return;
                }
                String customName = playerPetProfile.customNamesByPetId().getOrDefault(activePetId, "");
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(ownerUuid);
                    if (online == null || !online.isOnline()) {
                        return;
                    }
                    summonResolvedPet(online, playerPetProfile, pet.get(), customName, reason);
                });
            } catch (Exception exception) {
                plugin.getLogger().warning("BonfirePets runtime failed to resolve active pet for " + ownerUuid + ": " + exception.getMessage());
            }
        });
    }

    public void queueRefreshOnlinePlayer(UUID playerUuid, String reason) {
        if (playerUuid == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null && player.isOnline()) {
                queueEnsureActivePet(player, reason);
            }
        });
    }

    public void queueDismissActivePet(UUID ownerUuid, String reason) {
        if (ownerUuid == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> dismissActivePet(ownerUuid, reason));
    }

    public void dismissActivePet(UUID ownerUuid, String reason) {
        if (ownerUuid == null) {
            return;
        }
        ActivePetSession session = sessionsByOwner.remove(ownerUuid);
        if (session == null) {
            return;
        }
        ownerByPetEntity.remove(session.entityUuid(), ownerUuid);
        forcedDismounts.remove(session.entityUuid());

        markForcedDismount(session.entityUuid());
        betterModelAdapter.dismountAll(session.entityUuid());

        Entity entity = session.entity();
        if (entity != null) {
            String despawnSkill = session.petDefinition().behavior().despawnSkill();
            if (despawnSkill != null && !despawnSkill.isBlank()) {
                mythicMobsRuntimeAdapter.castSkill(entity, despawnSkill);
            }
        }
        betterModelAdapter.removeTracker(session.entityUuid(), session.betterModelId());
        betterModelAdapter.closeRegistry(session.entityUuid());
        if (entity != null) {
            mythicMobsRuntimeAdapter.removeMob(entity);
        }
        plugin.getLogger().info("BonfirePets dismissed pet " + session.petId() + " for " + ownerUuid + " reason=" + reason);
    }

    public void clearSessionByPetEntity(UUID entityUuid, String reason) {
        if (entityUuid == null) {
            return;
        }
        UUID ownerUuid = ownerByPetEntity.remove(entityUuid);
        forcedDismounts.remove(entityUuid);
        if (ownerUuid == null) {
            return;
        }
        sessionsByOwner.computeIfPresent(ownerUuid, (ignored, session) -> entityUuid.equals(session.entityUuid()) ? null : session);
        plugin.getLogger().info("BonfirePets cleared runtime session for pet entity " + entityUuid + " reason=" + reason);
    }

    public void handlePossibleDamage(Entity entity) {
        if (entity == null) {
            return;
        }
        ActivePetSession session = findRelatedSession(entity.getUniqueId());
        if (session == null || !session.mountable() || !session.petDefinition().mount().damageDismount()) {
            return;
        }
        markForcedDismount(session.entityUuid());
        betterModelAdapter.dismountAll(session.entityUuid());
    }

    public List<String> debugSession(UUID playerUuid) {
        List<String> lines = new ArrayList<>();
        ActivePetSession session = sessionsByOwner.get(playerUuid);
        if (session == null) {
            lines.add("[BonfirePets] runtime activeSession=none");
            return lines;
        }
        Entity entity = session.entity();
        lines.add("[BonfirePets] runtime pet=" + session.petId()
                + " entity=" + session.entityUuid()
                + " valid=" + (entity != null && entity.isValid())
                + " world=" + session.worldName()
                + " reason=" + session.spawnReason());
        lines.add("[BonfirePets] runtime model=" + session.betterModelId()
                + " mythic=" + session.mythicMobId()
                + " mythicType=" + (entity == null ? "none" : mythicMobsRuntimeAdapter.activeMobType(entity).orElse("unknown"))
                + " mountable=" + session.mountable()
                + " forcedDismount=" + forcedDismounts.containsKey(session.entityUuid()));
        return lines;
    }

    public UUID resolveDebugEntity(UUID playerUuid) {
        ActivePetSession session = sessionsByOwner.get(playerUuid);
        return session == null ? playerUuid : session.entityUuid();
    }

    public void handleMountEvent(BetterModelAdapter.ModelEventContext context) {
        ActivePetSession session = findPetSession(context.sourceEntityUuid());
        if (session == null) {
            return;
        }
        if (!session.mountable()) {
            context.setCancelled(true);
            return;
        }
        if (context.passengerUuid() == null || !context.passengerUuid().equals(session.ownerUuid())) {
            context.setCancelled(true);
            return;
        }
        Player owner = Bukkit.getPlayer(session.ownerUuid());
        if (owner == null || !owner.isOnline()) {
            context.setCancelled(true);
            return;
        }
        String permission = session.petDefinition().mount().mountPermission();
        if (permission != null && !permission.isBlank() && !owner.hasPermission(permission)) {
            context.setCancelled(true);
        }
    }

    public void handleDismountEvent(BetterModelAdapter.ModelEventContext context) {
        ActivePetSession session = findPetSession(context.sourceEntityUuid());
        if (session == null) {
            return;
        }
        if (forcedDismounts.containsKey(session.entityUuid())) {
            return;
        }
        if (context.passengerUuid() != null && !context.passengerUuid().equals(session.ownerUuid())) {
            return;
        }
        if (!session.petDefinition().mount().canDismountBySelf()) {
            context.setCancelled(true);
            return;
        }
        if (session.petDefinition().behavior().despawnOnDismount()) {
            Bukkit.getScheduler().runTask(plugin, () -> dismissActivePet(session.ownerUuid(), "dismount"));
        }
    }

    @Override
    public void close() {
        Runnable closeTask = () -> {
            new ArrayList<>(sessionsByOwner.keySet()).forEach(ownerUuid -> dismissActivePet(ownerUuid, "plugin-disable"));
            if (listener != null) {
                HandlerList.unregisterAll(listener);
                listener = null;
            }
        };
        if (Bukkit.isPrimaryThread()) {
            closeTask.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, closeTask);
        }
    }

    private void summonResolvedPet(Player player,
                                   PlayerPetProfile profile,
                                   PetDefinition pet,
                                   String customName,
                                   String reason) {
        if (!mythicMobsRuntimeAdapter.available()) {
            plugin.getLogger().warning("BonfirePets runtime cannot summon because MythicMobs is unavailable.");
            return;
        }
        if (pet.renderer().mythicMobId() == null || pet.renderer().mythicMobId().isBlank()) {
            plugin.getLogger().warning("BonfirePets runtime cannot summon " + pet.petId() + " because MythicMob id is blank.");
            return;
        }
        if (pet.renderer().betterModelId() == null || pet.renderer().betterModelId().isBlank()) {
            plugin.getLogger().warning("BonfirePets runtime cannot summon " + pet.petId() + " because BetterModel id is blank.");
            return;
        }
        if (!betterModelAdapter.modelExists(pet.renderer().betterModelId())) {
            plugin.getLogger().warning("BonfirePets runtime cannot summon " + pet.petId() + " because BetterModel model is missing: " + pet.renderer().betterModelId());
            return;
        }

        ActivePetSession current = sessionsByOwner.get(player.getUniqueId());
        String displayName = customName != null && !customName.isBlank() ? customName : pet.displayName();
        if (isReusableSession(current, player, pet)) {
            Entity existing = current.entity();
            if (existing != null) {
                applyCustomName(existing, displayName);
                betterModelAdapter.createOrGetTracker(existing, pet.renderer().betterModelId());
                betterModelAdapter.spawnForPlayer(existing.getUniqueId(), player);
                scheduleMountSetup(current, player, pet.behavior().autoRide());
            }
            return;
        }
        dismissActivePet(player.getUniqueId(), reason + "-replace");

        try {
            Location spawnLocation = player.getLocation().clone();
            Entity entity = mythicMobsRuntimeAdapter.spawnMythicMob(pet.renderer().mythicMobId(), spawnLocation);
            if (entity == null) {
                plugin.getLogger().warning("BonfirePets runtime failed to spawn MythicMob " + pet.renderer().mythicMobId() + " for " + player.getUniqueId());
                return;
            }

            tagEntity(entity, player.getUniqueId(), pet.petId());
            applyCustomName(entity, displayName);
            mythicMobsRuntimeAdapter.setOwner(entity, player.getUniqueId());

            if (betterModelAdapter.createOrGetTracker(entity, pet.renderer().betterModelId()).isEmpty()) {
                plugin.getLogger().warning("BonfirePets runtime failed to attach BetterModel tracker " + pet.renderer().betterModelId() + " to " + entity.getUniqueId());
                mythicMobsRuntimeAdapter.removeMob(entity);
                return;
            }
            betterModelAdapter.spawnForPlayer(entity.getUniqueId(), player);

            ActivePetSession session = new ActivePetSession(
                    player.getUniqueId(),
                    profile.playerName(),
                    pet.petId(),
                    pet.renderer().mythicMobId(),
                    pet.renderer().betterModelId(),
                    entity.getUniqueId(),
                    entity.getWorld().getName(),
                    reason,
                    pet,
                    Instant.now()
            );
            sessionsByOwner.put(player.getUniqueId(), session);
            ownerByPetEntity.put(entity.getUniqueId(), player.getUniqueId());
            scheduleMountSetup(session, player, pet.behavior().autoRide());
            plugin.getLogger().info("BonfirePets summoned pet " + pet.petId() + " for " + player.getUniqueId() + " reason=" + reason);
        } catch (Exception exception) {
            plugin.getLogger().warning("BonfirePets runtime failed to summon " + pet.petId() + " for " + player.getUniqueId() + ": " + exception.getMessage());
        }
    }

    private boolean isReusableSession(ActivePetSession session, Player player, PetDefinition pet) {
        if (session == null || !session.petId().equalsIgnoreCase(pet.petId())) {
            return false;
        }
        Entity entity = session.entity();
        return entity != null
                && entity.isValid()
                && !entity.isDead()
                && entity.getWorld().getUID().equals(player.getWorld().getUID());
    }

    private void scheduleMountSetup(ActivePetSession session, Player player, boolean autoRide) {
        if (session == null || player == null || !player.isOnline()) {
            return;
        }
        if (!session.mountable() && !autoRide) {
            return;
        }
        scheduleMountAttempt(session, player, autoRide, REMOUNT_RETRY_1);
        scheduleMountAttempt(session, player, autoRide, REMOUNT_RETRY_2);
        scheduleMountAttempt(session, player, autoRide, REMOUNT_RETRY_3);
    }

    private void scheduleMountAttempt(ActivePetSession session, Player player, boolean autoRide, long delay) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ActivePetSession current = sessionsByOwner.get(session.ownerUuid());
            if (current == null || !current.entityUuid().equals(session.entityUuid()) || !player.isOnline()) {
                return;
            }
            boolean configured = betterModelAdapter.configureFirstMountController(current.entityUuid(), current.betterModelId(), current.petDefinition().mount());
            if (autoRide && configured) {
                betterModelAdapter.mountPlayer(current.entityUuid(), current.betterModelId(), player);
            }
        }, delay);
    }

    private void tagEntity(Entity entity, UUID ownerUuid, String petId) {
        PersistentDataContainer container = entity.getPersistentDataContainer();
        container.set(managedKey, PersistentDataType.BYTE, (byte) 1);
        container.set(ownerKey, PersistentDataType.STRING, ownerUuid.toString());
        container.set(petIdKey, PersistentDataType.STRING, petId);
        entity.addScoreboardTag("bonfirepets");
        entity.addScoreboardTag("bonfirepets:" + petId.toLowerCase());
    }

    private void applyCustomName(Entity entity, String customName) {
        if (entity == null) {
            return;
        }
        if (customName == null || customName.isBlank()) {
            entity.customName(null);
            entity.setCustomNameVisible(false);
            return;
        }
        Component component = customName.indexOf('§') >= 0
                ? LegacyComponentSerializer.legacySection().deserialize(customName)
                : LegacyComponentSerializer.legacyAmpersand().deserialize(customName);
        entity.customName(component);
        entity.setCustomNameVisible(false);
    }

    private void markForcedDismount(UUID entityUuid) {
        if (entityUuid == null) {
            return;
        }
        forcedDismounts.put(entityUuid, Boolean.TRUE);
        Bukkit.getScheduler().runTaskLater(plugin, () -> forcedDismounts.remove(entityUuid), STARTUP_SUMMON_DELAY_TICKS);
    }

    private ActivePetSession findPetSession(UUID petEntityUuid) {
        if (petEntityUuid == null) {
            return null;
        }
        UUID ownerUuid = ownerByPetEntity.get(petEntityUuid);
        return ownerUuid == null ? null : sessionsByOwner.get(ownerUuid);
    }

    private ActivePetSession findRelatedSession(UUID uuid) {
        ActivePetSession byOwner = sessionsByOwner.get(uuid);
        if (byOwner != null) {
            return byOwner;
        }
        return findPetSession(uuid);
    }

    boolean isManagedPet(Entity entity) {
        if (entity == null) {
            return false;
        }
        Byte managed = entity.getPersistentDataContainer().get(managedKey, PersistentDataType.BYTE);
        return managed != null && managed == (byte) 1;
    }
}
