package me.matl114.hacks.modules;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import me.matl114.hacks.api.ModuleGroup;
import me.matl114.hacks.api.ViewModuleGroup;
import me.matl114.utils.ApiMethod;

@ApiMethod
public class HackModules {

    public static final ModuleMain main = new ModuleMain();

    /** Plugin-provided read-only groups; deliberately kept out of {@link #main}. */
    private static final Map<String, ViewModuleGroup> viewGroups = new LinkedHashMap<>();

    public static void registerModuleGroup(ModuleGroup group) {
        main.registerModule(group);
    }

    public static void registerViewGroup(ViewModuleGroup group) {
        viewGroups.put(group.getName(), group);
    }

    public static ModuleGroup getModuleGroup(String name) {
        ModuleGroup group = main.moduleGroups.get(name);
        return group != null ? group : viewGroups.get(name);
    }

    public static Collection<ModuleGroup> getModuleGroups() {
        return main.moduleGroups.values();
    }

    /** View groups are only for rendering; search, commands and translation checks skip them. */
    public static Collection<ViewModuleGroup> getViewGroups() {
        return viewGroups.values();
    }

    public static void reloadModuleGroups() {
        main.reloadModules();
    }

    public static void init() {}
}
