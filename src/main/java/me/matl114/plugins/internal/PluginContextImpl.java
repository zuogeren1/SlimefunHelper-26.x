package me.matl114.plugins.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import me.matl114.hacks.ChatTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModuleGroup;
import me.matl114.hacks.modules.HackModules;
import me.matl114.hacks.modules.move.TravellingControl;
import me.matl114.managers.config.Config;
import me.matl114.managers.config.ConfigLoader;
import me.matl114.plugins.api.PluginContext;
import me.matl114.utils.CommonUtils;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;

/** Host implementation of {@link PluginContext}: pure forwarding plus plugin/module ownership. */
final class PluginContextImpl implements PluginContext {
    /** Owner mod id per module, used to label plugin modules in the GUI. */
    private final Map<BaseModule, String> moduleOwners = new LinkedHashMap<>();

    private final Map<String, List<BaseModule>> pluginModules = new LinkedHashMap<>();

    private final Map<String, ViewGroupSpec> viewGroups = new LinkedHashMap<>();

    private final Chat chat = (text, addToHistory) -> ChatTasks.sayMessage(text, addToHistory);

    private final Travel travel = () -> {
        TravellingControl.TravelInfo task = TravellingControl.travelTask;
        return task == null ? null : task.getCurrentFlyingTarget();
    };

    private final World world = CommonUtils::chunks;

    private final Modules modules = new Modules() {
        @Override
        public ModuleGroup group(String name) {
            return HackModules.getModuleGroup(name);
        }

        @Override
        public void registerGroup(String pluginId, ModuleGroup group) {
            HackModules.registerModuleGroup(group);
            List<BaseModule> owned = pluginModules.computeIfAbsent(pluginId, key -> new ArrayList<>());
            for (BaseModule module : group.getModules()) {
                moduleOwners.put(module, pluginId);
                owned.add(module);
            }
        }

        @Override
        public void registerViewGroup(String pluginId, String name, Supplier<List<BaseModule>> supplier) {
            viewGroups.put(pluginId + ':' + name, new ViewGroupSpec(pluginId, name, supplier));
        }

        @Override
        public List<BaseModule> modulesOf(String pluginId) {
            return List.copyOf(pluginModules.getOrDefault(pluginId, List.of()));
        }
    };

    private final Configs configs = new Configs() {
        @Override
        public Config load(String relativePath, String displayName) {
            return ConfigLoader.loadExternalConfig(relativePath, displayName).markForSave();
        }

        @Override
        public <T extends Config> T register(T config) {
            config.registerGlobal(null);
            return config;
        }
    };

    @Override
    public Chat chat() {
        return chat;
    }

    @Override
    public Travel travel() {
        return travel;
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public Modules modules() {
        return modules;
    }

    @Override
    public Configs config() {
        return configs;
    }

    /** @return the owning plugin id of a module, or {@code null} for built-in modules. */
    String ownerOf(BaseModule module) {
        return moduleOwners.get(module);
    }

    List<ViewGroupSpec> viewGroups() {
        return List.copyOf(viewGroups.values());
    }

    record ViewGroupSpec(String pluginId, String name, Supplier<List<BaseModule>> modules) {}
}
