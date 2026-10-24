package me.matl114.hacks.api;

import java.util.List;
import java.util.function.Supplier;

/**
 * A read-only {@link ModuleGroup} that renders modules owned by someone else instead of
 * registering them. It exists so a plugin can offer a "all my modules" column without
 * duplicating module instances (a module may only have one {@code manager}).
 */
public class ViewModuleGroup extends ModuleGroup {
    private final Supplier<List<BaseModule>> provider;

    public ViewModuleGroup(String name, Supplier<List<BaseModule>> provider) {
        super(name);
        this.provider = provider;
    }

    @Override
    public List<BaseModule> getModules() {
        List<BaseModule> modules = provider.get();
        return modules == null ? List.of() : List.copyOf(modules);
    }

    @Override
    public void registerModule(BaseModule module) {
        throw new UnsupportedOperationException("ViewModuleGroup is read-only: " + getName());
    }
}
