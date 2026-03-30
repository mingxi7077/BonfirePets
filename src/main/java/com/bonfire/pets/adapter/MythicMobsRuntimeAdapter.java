package com.bonfire.pets.adapter;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

public final class MythicMobsRuntimeAdapter {

    private static final String MYTHIC_BUKKIT_CLASS = "io.lumine.mythic.bukkit.MythicBukkit";

    public boolean available() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("MythicMobs");
        return plugin != null && plugin.isEnabled();
    }

    public String version() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("MythicMobs");
        return plugin == null ? "" : plugin.getDescription().getVersion();
    }

    public Entity spawnMythicMob(String mythicMobId, Location location) throws Exception {
        if (mythicMobId == null || mythicMobId.isBlank()) {
            throw new IllegalArgumentException("Missing MythicMob id.");
        }
        Object helper = helper();
        Method spawn = helper.getClass().getMethod("spawnMythicMob", String.class, Location.class);
        return (Entity) spawn.invoke(helper, mythicMobId, location);
    }

    public boolean isMythicMob(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            Object helper = helper();
            Method method = helper.getClass().getMethod("isMythicMob", Entity.class);
            return (boolean) method.invoke(helper, entity);
        } catch (Exception ignored) {
            return false;
        }
    }

    public Optional<Object> activeMob(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        try {
            Object helper = helper();
            Method method = helper.getClass().getMethod("getMythicMobInstance", Entity.class);
            return Optional.ofNullable(method.invoke(helper, entity));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public Optional<String> activeMobType(Entity entity) {
        return activeMob(entity).flatMap(activeMob -> invokeString(activeMob, "getMobType"));
    }

    public boolean castSkill(Entity entity, String skillId) {
        if (entity == null || skillId == null || skillId.isBlank()) {
            return false;
        }
        try {
            Object helper = helper();
            Method method = helper.getClass().getMethod("castSkill", Entity.class, String.class);
            return (boolean) method.invoke(helper, entity, skillId);
        } catch (Exception ignored) {
            return false;
        }
    }

    public void setOwner(Entity entity, UUID ownerUuid) {
        if (entity == null || ownerUuid == null) {
            return;
        }
        activeMob(entity).ifPresent(activeMob -> {
            try {
                Method method = activeMob.getClass().getMethod("setOwnerUUID", UUID.class);
                method.invoke(activeMob, ownerUuid);
            } catch (Exception ignored) {
            }
        });
    }

    public boolean removeMob(Entity entity) {
        if (entity == null) {
            return false;
        }
        boolean handled = false;
        Optional<Object> activeMob = activeMob(entity);
        if (activeMob.isPresent()) {
            handled = invokeVoid(activeMob.get(), "remove")
                    || invokeVoid(activeMob.get(), "despawn")
                    || invokeVoid(activeMob.get(), "setDead")
                    || invokeVoid(activeMob.get(), "unregister")
                    || invokeVoid(activeMob.get(), "setDespawnedSync");
        }
        if (!entity.isDead() && entity.isValid()) {
            entity.remove();
            handled = true;
        }
        return handled;
    }

    private Object helper() throws Exception {
        Class<?> mythicBukkit = Class.forName(MYTHIC_BUKKIT_CLASS);
        Method inst = mythicBukkit.getMethod("inst");
        Object instance = inst.invoke(null);
        Method helper = mythicBukkit.getMethod("getAPIHelper");
        return helper.invoke(instance);
    }

    private Optional<String> invokeString(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            return value == null ? Optional.empty() : Optional.of(value.toString());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private boolean invokeVoid(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            method.invoke(target);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
