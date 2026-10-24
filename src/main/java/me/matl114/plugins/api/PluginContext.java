package me.matl114.plugins.api;

import java.util.List;
import java.util.function.Supplier;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModuleGroup;
import me.matl114.managers.config.Config;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;

/**
 * The narrow surface a plugin is allowed to use. Everything outside this interface (and
 * {@link SlimefunHelperPlugin}) is host internals and may change without notice.
 *
 * <p>Bump {@link #API_VERSION} whenever a method is removed or its contract changes;
 * adding a method is backwards compatible.
 */
public interface PluginContext {
    int API_VERSION = 1;

    Chat chat();

    Travel travel();

    World world();

    Modules modules();

    Configs config();

    interface Chat {
        /** Sends a chat message from the client. */
        void say(String text, boolean addToHistory);
    }

    interface Travel {
        /** @return the current flying target of the travel task, or {@code null} when not travelling. */
        Vec3 currentFlyingTarget();
    }

    interface World {
        /** Iterates loaded chunks, optionally requiring all neighbours to be loaded too. */
        Iterable<ChunkAccess> chunks(boolean onlyWithLoadedNeighbours);
    }

    interface Modules {
        /** @return the module group registered under {@code name}, or {@code null}. */
        ModuleGroup group(String name);

        /** Registers a real module group owned by {@code pluginId}. */
        void registerGroup(String pluginId, ModuleGroup group);

        /** Registers a single module into an existing host group (e.g. {@code "Survival"}). */
        void register(String pluginId, String groupName, BaseModule module);

        /** Registers a read-only view group listing modules owned by {@code pluginId}. */
        void registerViewGroup(String pluginId, String name, Supplier<List<BaseModule>> modules);

        /** @return the modules registered by {@code pluginId}, in registration order. */
        List<BaseModule> modulesOf(String pluginId);
    }

    interface Configs {
        /** Loads (or creates) a plugin-owned config file and enables auto save. */
        Config load(String relativePath, String displayName);

        /** Makes a plugin config visible to the global config screen and reload lifecycle. */
        <T extends Config> T register(T config);
    }
}
