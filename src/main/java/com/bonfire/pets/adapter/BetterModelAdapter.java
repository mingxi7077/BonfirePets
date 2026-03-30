package com.bonfire.pets.adapter;

import com.bonfire.pets.model.BetterModelProbe;
import com.bonfire.pets.model.MountSpec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class BetterModelAdapter {

    private static final String BETTER_MODEL_CLASS = "kr.toxicity.model.api.BetterModel";
    private static final String BETTER_MODEL_BUKKIT_CLASS = "kr.toxicity.model.api.bukkit.BetterModelBukkit";
    private static final String BUKKIT_ADAPTER_CLASS = "kr.toxicity.model.api.bukkit.platform.BukkitAdapter";
    private static final String PLATFORM_ENTITY_CLASS = "kr.toxicity.model.api.platform.PlatformEntity";
    private static final String PLATFORM_PLAYER_CLASS = "kr.toxicity.model.api.platform.PlatformPlayer";
    private static final String TRACKER_MODIFIER_CLASS = "kr.toxicity.model.api.tracker.TrackerModifier";
    private static final String HITBOX_LISTENER_CLASS = "kr.toxicity.model.api.nms.HitBoxListener";
    private static final String MOUNT_CONTROLLER_CLASS = "kr.toxicity.model.api.mount.MountController";
    private static final String MOUNT_CONTROLLERS_CLASS = "kr.toxicity.model.api.mount.MountControllers";
    private static final String MOUNT_EVENT_CLASS = "kr.toxicity.model.api.event.MountModelEvent";
    private static final String DISMOUNT_EVENT_CLASS = "kr.toxicity.model.api.event.DismountModelEvent";

    private final JavaPlugin plugin;
    private final boolean registerHooks;
    private final Deque<String> recentEvents = new ArrayDeque<>();
    private final List<Consumer<ModelEventContext>> mountListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ModelEventContext>> dismountListeners = new CopyOnWriteArrayList<>();

    public BetterModelAdapter(JavaPlugin plugin, boolean registerHooks) {
        this.plugin = plugin;
        this.registerHooks = registerHooks;
    }

    public void initialize() {
        if (!registerHooks) {
            return;
        }
        BetterModelProbe probe = probe();
        if (!probe.pluginPresent() || !probe.apiAvailable() || !probe.eventBusAvailable()) {
            return;
        }
        try {
            Object eventBus = eventBus();
            Method subscribe = eventBus.getClass().getMethod("subscribe", Plugin.class, Class.class, Consumer.class);
            Class<?> mountEvent = Class.forName(MOUNT_EVENT_CLASS);
            Class<?> dismountEvent = Class.forName(DISMOUNT_EVENT_CLASS);
            subscribe.invoke(eventBus, plugin, mountEvent, consumerFor("mount", mountListeners));
            subscribe.invoke(eventBus, plugin, dismountEvent, consumerFor("dismount", dismountListeners));
        } catch (Exception exception) {
            pushEvent("hook-failed:" + exception.getMessage());
        }
    }

    public void registerMountListener(Consumer<ModelEventContext> listener) {
        mountListeners.add(listener);
    }

    public void registerDismountListener(Consumer<ModelEventContext> listener) {
        dismountListeners.add(listener);
    }

    public BetterModelProbe probe() {
        Plugin pluginInstance = Bukkit.getPluginManager().getPlugin("BetterModel");
        if (pluginInstance == null) {
            return new BetterModelProbe(false, "", false, false, 0, snapshotEvents(), "plugin missing");
        }
        try {
            Class<?> betterModel = Class.forName(BETTER_MODEL_CLASS);
            Method modelKeys = betterModel.getMethod("modelKeys");
            Object keys = modelKeys.invoke(null);
            int loadedModels = keys instanceof Collection<?> collection ? collection.size() : 0;
            boolean eventBusAvailable = eventBus() != null;
            return new BetterModelProbe(true, pluginInstance.getDescription().getVersion(), true, eventBusAvailable, loadedModels, snapshotEvents(), "ok");
        } catch (Exception exception) {
            return new BetterModelProbe(true, pluginInstance.getDescription().getVersion(), false, false, 0, snapshotEvents(), exception.getMessage());
        }
    }

    public boolean modelExists(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        return renderer(modelId).isPresent();
    }

    public Optional<Object> createOrGetTracker(Entity entity, String modelId) {
        if (entity == null || modelId == null || modelId.isBlank()) {
            return Optional.empty();
        }
        try {
            Object renderer = renderer(modelId).orElse(null);
            if (renderer == null) {
                return Optional.empty();
            }
            Method method = renderer.getClass().getMethod("getOrCreate", Class.forName(PLATFORM_ENTITY_CLASS));
            return Optional.ofNullable(method.invoke(renderer, adaptEntity(entity)));
        } catch (Exception exception) {
            pushEvent("tracker-create-failed:" + modelId + ":" + exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public boolean spawnForPlayer(UUID entityUuid, Player player) {
        if (entityUuid == null || player == null) {
            return false;
        }
        try {
            Optional<?> registry = registry(entityUuid);
            if (registry.isEmpty()) {
                return false;
            }
            Method method = registry.get().getClass().getMethod("spawnIfNotSpawned", Class.forName(PLATFORM_PLAYER_CLASS));
            return (boolean) method.invoke(registry.get(), adaptPlayer(player));
        } catch (Exception exception) {
            pushEvent("registry-spawn-failed:" + entityUuid + ":" + exception.getClass().getSimpleName());
            return false;
        }
    }

    public boolean removeTracker(UUID entityUuid, String modelId) {
        if (entityUuid == null || modelId == null || modelId.isBlank()) {
            return false;
        }
        try {
            Optional<?> registry = registry(entityUuid);
            if (registry.isEmpty()) {
                return false;
            }
            Method method = registry.get().getClass().getMethod("remove", String.class);
            return (boolean) method.invoke(registry.get(), modelId);
        } catch (Exception exception) {
            pushEvent("tracker-remove-failed:" + modelId + ":" + exception.getClass().getSimpleName());
            return false;
        }
    }

    public boolean closeRegistry(UUID entityUuid) {
        if (entityUuid == null) {
            return false;
        }
        try {
            Optional<?> registry = registry(entityUuid);
            if (registry.isEmpty()) {
                return false;
            }
            Method method = registry.get().getClass().getMethod("close");
            return (boolean) method.invoke(registry.get());
        } catch (Exception exception) {
            pushEvent("registry-close-failed:" + entityUuid + ":" + exception.getClass().getSimpleName());
            return false;
        }
    }

    public boolean dismountAll(UUID entityUuid) {
        if (entityUuid == null) {
            return false;
        }
        try {
            Optional<?> registry = registry(entityUuid);
            if (registry.isEmpty()) {
                return false;
            }
            Method mountedHitBoxMethod = registry.get().getClass().getMethod("mountedHitBox");
            Object value = mountedHitBoxMethod.invoke(registry.get());
            if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
                return false;
            }
            boolean changed = false;
            for (Object mounted : map.values()) {
                Method dismountAll = mounted.getClass().getMethod("dismountAll");
                dismountAll.invoke(mounted);
                changed = true;
            }
            return changed;
        } catch (Exception exception) {
            pushEvent("dismount-all-failed:" + entityUuid + ":" + exception.getClass().getSimpleName());
            return false;
        }
    }

    public boolean configureFirstMountController(UUID entityUuid, String modelId, MountSpec mountSpec) {
        if (entityUuid == null || mountSpec == null) {
            return false;
        }
        try {
            Object tracker = tracker(entityUuid, modelId).orElse(null);
            if (tracker == null) {
                return false;
            }
            Method hitboxMethod = tracker.getClass().getMethod("hitbox", Class.forName(HITBOX_LISTENER_CLASS), Predicate.class);
            Object hitbox = hitboxMethod.invoke(tracker, null, (Predicate<Object>) ignored -> true);
            if (hitbox == null) {
                return false;
            }
            Object controller = buildMountController(mountSpec);
            Method mountController = hitbox.getClass().getMethod("mountController", Class.forName(MOUNT_CONTROLLER_CLASS));
            mountController.invoke(hitbox, controller);
            return true;
        } catch (Exception exception) {
            pushEvent("mount-controller-failed:" + entityUuid + ":" + exception.getClass().getSimpleName());
            return false;
        }
    }

    public boolean mountPlayer(UUID entityUuid, String modelId, Player player) {
        if (entityUuid == null || player == null) {
            return false;
        }
        try {
            Object tracker = tracker(entityUuid, modelId).orElse(null);
            if (tracker == null) {
                return false;
            }
            Method hitboxMethod = tracker.getClass().getMethod("hitbox", Class.forName(HITBOX_LISTENER_CLASS), Predicate.class);
            Object hitbox = hitboxMethod.invoke(tracker, null, (Predicate<Object>) ignored -> true);
            if (hitbox == null) {
                return false;
            }
            Method mount = hitbox.getClass().getMethod("mount", Class.forName(PLATFORM_ENTITY_CLASS));
            mount.invoke(hitbox, adaptEntity(player));
            return true;
        } catch (Exception exception) {
            pushEvent("mount-player-failed:" + entityUuid + ":" + exception.getClass().getSimpleName());
            return false;
        }
    }

    public List<String> debugTracker(UUID entityUuid, String input) {
        List<String> lines = new ArrayList<>();
        BetterModelProbe probe = probe();
        lines.add("[BonfirePets] BetterModel plugin=" + probe.pluginPresent() + " api=" + probe.apiAvailable() + " models=" + probe.loadedModelCount());
        if (!probe.apiAvailable()) {
            lines.add("[BonfirePets] BetterModel unavailable: " + probe.message());
            return lines;
        }
        try {
            Optional<?> registry = registry(entityUuid);
            if (registry.isEmpty()) {
                lines.add("[BonfirePets] No BetterModel tracker registry for " + input + " (" + entityUuid + ")");
                return lines;
            }
            Object value = registry.get();
            Method trackersMethod = value.getClass().getMethod("trackers");
            Method hitBoxesMethod = value.getClass().getMethod("hitBoxes");
            Method mountedHitBoxMethod = value.getClass().getMethod("mountedHitBox");
            Method hasPassengerMethod = value.getClass().getMethod("hasPassenger");
            Collection<?> trackers = (Collection<?>) trackersMethod.invoke(value);
            Collection<?> hitboxes = (Collection<?>) hitBoxesMethod.invoke(value);
            Object mounted = mountedHitBoxMethod.invoke(value);
            boolean hasPassenger = (boolean) hasPassengerMethod.invoke(value);
            lines.add("[BonfirePets] registry trackers=" + trackers.size() + " hitboxes=" + hitboxes.size() + " mounted=" + mounted);
            lines.add("[BonfirePets] hasPassenger=" + hasPassenger + " recentEvents=" + snapshotEvents());
        } catch (Exception exception) {
            lines.add("[BonfirePets] Tracker debug failed: " + exception.getMessage());
        }
        return lines;
    }

    public List<String> debugMount(UUID entityUuid, String input) {
        List<String> lines = new ArrayList<>();
        BetterModelProbe probe = probe();
        lines.add("[BonfirePets] BetterModel plugin=" + probe.pluginPresent() + " api=" + probe.apiAvailable() + " eventBus=" + probe.eventBusAvailable());
        if (!probe.apiAvailable()) {
            lines.add("[BonfirePets] BetterModel unavailable: " + probe.message());
            return lines;
        }
        try {
            Optional<?> registry = registry(entityUuid);
            if (registry.isEmpty()) {
                lines.add("[BonfirePets] No BetterModel mount registry for " + input + " (" + entityUuid + ")");
                return lines;
            }
            Object value = registry.get();
            Method hasPassengerMethod = value.getClass().getMethod("hasPassenger");
            Method hasControllingPassengerMethod = value.getClass().getMethod("hasControllingPassenger");
            Method mountedHitBoxMethod = value.getClass().getMethod("mountedHitBox");
            boolean hasPassenger = (boolean) hasPassengerMethod.invoke(value);
            boolean hasControllingPassenger = (boolean) hasControllingPassengerMethod.invoke(value);
            Object mounted = mountedHitBoxMethod.invoke(value);
            lines.add("[BonfirePets] hasPassenger=" + hasPassenger + " controlling=" + hasControllingPassenger);
            lines.add("[BonfirePets] mountedHitBox=" + mounted);
            lines.add("[BonfirePets] recentEvents=" + snapshotEvents());
        } catch (Exception exception) {
            lines.add("[BonfirePets] Mount debug failed: " + exception.getMessage());
        }
        return lines;
    }

    private Optional<Object> renderer(String modelId) {
        try {
            Class<?> betterModel = Class.forName(BETTER_MODEL_CLASS);
            Method modelOrNull = betterModel.getMethod("modelOrNull", String.class);
            return Optional.ofNullable(modelOrNull.invoke(null, modelId));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<?> registry(UUID entityUuid) throws Exception {
        Class<?> betterModel = Class.forName(BETTER_MODEL_CLASS);
        Method registry = betterModel.getMethod("registry", UUID.class);
        Object optional = registry.invoke(null, entityUuid);
        return optional instanceof Optional<?> cast ? cast : Optional.empty();
    }

    private Optional<Object> tracker(UUID entityUuid, String modelId) throws Exception {
        Optional<?> registry = registry(entityUuid);
        if (registry.isEmpty()) {
            return Optional.empty();
        }
        Object registryValue = registry.get();
        if (modelId != null && !modelId.isBlank()) {
            Method trackerMethod = registryValue.getClass().getMethod("tracker", String.class);
            Object tracker = trackerMethod.invoke(registryValue, modelId);
            if (tracker != null) {
                return Optional.of(tracker);
            }
        }
        Method first = registryValue.getClass().getMethod("first");
        return Optional.ofNullable(first.invoke(registryValue));
    }

    private Object buildMountController(MountSpec mountSpec) throws Exception {
        @SuppressWarnings("unchecked")
        Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>) Class.forName(MOUNT_CONTROLLERS_CLASS).asSubclass(Enum.class);
        String key = mountSpec.mountable()
                ? (mountSpec.flying() ? "FLY" : "WALK")
                : "NONE";
        Object controller = Enum.valueOf((Class) enumClass, key);
        Method modifier = controller.getClass().getMethod("modifier");
        Object value = modifier.invoke(controller);
        invokeBuilder(value, "canMount", mountSpec.mountable());
        invokeBuilder(value, "canControl", mountSpec.controllable());
        invokeBuilder(value, "canJump", mountSpec.canJump());
        invokeBuilder(value, "canFly", mountSpec.flying());
        invokeBuilder(value, "canDismountBySelf", mountSpec.canDismountBySelf());
        invokeBuilder(value, "canBeDamagedByRider", false);
        Method build = value.getClass().getMethod("build");
        return build.invoke(value);
    }

    private void invokeBuilder(Object target, String methodName, boolean value) throws Exception {
        Method method = target.getClass().getMethod(methodName, boolean.class);
        method.invoke(target, value);
    }

    private Object adaptEntity(Entity entity) throws Exception {
        Class<?> adapter = Class.forName(BUKKIT_ADAPTER_CLASS);
        Method adapt = adapter.getMethod("adapt", Entity.class);
        return adapt.invoke(null, entity);
    }

    private Object adaptPlayer(Player player) throws Exception {
        Class<?> adapter = Class.forName(BUKKIT_ADAPTER_CLASS);
        Method adapt = adapter.getMethod("adapt", Player.class);
        return adapt.invoke(null, player);
    }

    private Object eventBus() throws Exception {
        Class<?> betterModelBukkit = Class.forName(BETTER_MODEL_BUKKIT_CLASS);
        Method platform = betterModelBukkit.getMethod("platform");
        Object platformValue = platform.invoke(null);
        Method eventBus = platformValue.getClass().getMethod("eventBus");
        return eventBus.invoke(platformValue);
    }

    private Consumer<Object> consumerFor(String type, List<Consumer<ModelEventContext>> listeners) {
        return event -> {
            ModelEventContext context = toContext(type, event);
            pushEvent(type + ":" + context.trackerModelId() + ":" + context.passengerUuid());
            listeners.forEach(listener -> {
                try {
                    listener.accept(context);
                } catch (Exception exception) {
                    pushEvent(type + "-listener-failed:" + exception.getClass().getSimpleName());
                }
            });
        };
    }

    private ModelEventContext toContext(String type, Object event) {
        Object tracker = invoke(event, "tracker");
        String trackerModelId = stringValue(invoke(tracker, "name"));
        Object registry = invoke(tracker, "registry");
        Object sourceEntity = invoke(registry, "entity");
        UUID sourceEntityUuid = uuidValue(invoke(sourceEntity, "uuid"));
        Object passenger = invoke(event, "entity");
        UUID passengerUuid = uuidValue(invoke(passenger, "uuid"));
        String boneName = resolveBoneName(invoke(event, "bone"));
        Method setCancelled = findBooleanMethod(event.getClass(), "setCancelled");
        Method isCancelled = findZeroArgMethod(event.getClass(), "isCancelled");
        return new ModelEventContext(event, setCancelled, isCancelled, type, trackerModelId, sourceEntityUuid, passengerUuid, boneName);
    }

    private String resolveBoneName(Object bone) {
        Object boneName = invoke(bone, "name");
        Object actual = invoke(boneName, "name");
        return stringValue(actual == null ? boneName : actual);
    }

    private Object invoke(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Method findZeroArgMethod(Class<?> type, String methodName) {
        try {
            return type.getMethod(methodName);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Method findBooleanMethod(Class<?> type, String methodName) {
        try {
            return type.getMethod(methodName, boolean.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private UUID uuidValue(Object value) {
        return value instanceof UUID uuid ? uuid : null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private void pushEvent(String event) {
        while (recentEvents.size() >= 8) {
            recentEvents.removeFirst();
        }
        recentEvents.addLast(event);
    }

    private List<String> snapshotEvents() {
        return List.copyOf(recentEvents);
    }

    public static final class ModelEventContext {

        private final Object sourceEvent;
        private final Method setCancelledMethod;
        private final Method isCancelledMethod;
        private final String eventType;
        private final String trackerModelId;
        private final UUID sourceEntityUuid;
        private final UUID passengerUuid;
        private final String boneName;

        private ModelEventContext(Object sourceEvent,
                                  Method setCancelledMethod,
                                  Method isCancelledMethod,
                                  String eventType,
                                  String trackerModelId,
                                  UUID sourceEntityUuid,
                                  UUID passengerUuid,
                                  String boneName) {
            this.sourceEvent = sourceEvent;
            this.setCancelledMethod = setCancelledMethod;
            this.isCancelledMethod = isCancelledMethod;
            this.eventType = eventType;
            this.trackerModelId = trackerModelId == null ? "" : trackerModelId;
            this.sourceEntityUuid = sourceEntityUuid;
            this.passengerUuid = passengerUuid;
            this.boneName = boneName == null ? "" : boneName;
        }

        public String eventType() {
            return eventType;
        }

        public String trackerModelId() {
            return trackerModelId;
        }

        public UUID sourceEntityUuid() {
            return sourceEntityUuid;
        }

        public UUID passengerUuid() {
            return passengerUuid;
        }

        public String boneName() {
            return boneName;
        }

        public boolean cancelled() {
            if (isCancelledMethod == null) {
                return false;
            }
            try {
                return (boolean) isCancelledMethod.invoke(sourceEvent);
            } catch (Exception ignored) {
                return false;
            }
        }

        public void setCancelled(boolean cancelled) {
            if (setCancelledMethod == null) {
                return;
            }
            try {
                setCancelledMethod.invoke(sourceEvent, cancelled);
            } catch (Exception ignored) {
            }
        }
    }
}
