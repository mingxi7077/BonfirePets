package com.bonfire.pets.runtime;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class BonfirePetsListener implements Listener {

    private final PetRuntimeManager runtimeManager;

    public BonfirePetsListener(PetRuntimeManager runtimeManager) {
        this.runtimeManager = runtimeManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(runtimeManager.plugin(), () ->
                runtimeManager.queueEnsureActivePet(event.getPlayer(), "join"), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        runtimeManager.dismissActivePet(event.getPlayer().getUniqueId(), "quit");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        runtimeManager.dismissActivePet(event.getPlayer().getUniqueId(), "world-change");
        Bukkit.getScheduler().runTaskLater(runtimeManager.plugin(), () ->
                runtimeManager.queueEnsureActivePet(event.getPlayer(), "world-change"), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(runtimeManager.plugin(), () ->
                runtimeManager.queueEnsureActivePet(event.getPlayer(), "respawn"), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        runtimeManager.dismissActivePet(event.getEntity().getUniqueId(), "player-death");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (runtimeManager.isManagedPet(entity)) {
            runtimeManager.clearSessionByPetEntity(entity.getUniqueId(), "entity-death");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        runtimeManager.handlePossibleDamage(event.getEntity());
    }
}
