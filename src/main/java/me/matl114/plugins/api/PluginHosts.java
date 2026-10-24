package me.matl114.plugins.api;

/**
 * Host-only installation point for the {@link PluginContext} singleton.
 *
 * <p>Plugins must not call {@link #install(PluginContext)}; it is invoked once by
 * {@code me.matl114.plugins.internal.PluginManager} during bootstrap.
 */
public final class PluginHosts {
    private static PluginContext context;

    private PluginHosts() {}

    public static PluginContext context() {
        PluginContext current = context;
        if (current == null) {
            throw new IllegalStateException("PluginContext is not installed yet.");
        }
        return current;
    }

    public static void install(PluginContext pluginContext) {
        if (context != null && context != pluginContext) {
            throw new IllegalStateException("PluginContext already installed.");
        }
        context = pluginContext;
    }
}
