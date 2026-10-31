package me.matl114.gui.presets.index;

import java.util.List;
import me.matl114.gui.GenericScreen;
import me.matl114.gui.basic.*;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;

// todo: replace it with IndexedSubScreen
public abstract class IndexedScreen<T, W extends GuiEventListener & Renderable & NarratableEntry>
        extends GenericScreen {
    protected final List<T> configList;
    protected IndexedSubScreen<T, W> subScreenDelegate;

    public IndexedScreen(List<T> list, int backgroundWidth, int backgroundHeight) {
        super(Component.empty(), backgroundWidth, backgroundHeight);
        this.configList = list;
    }

    protected void createDelegate() {
        this.subScreenDelegate =
                new IndexedSubScreen<T, W>(
                        configList, 10, 10, this.width - 20, this.height - 20, configButtonWidth, buttonHeight) {
                    @Override
                    protected ElementHandler createIndexHandler(T val) {
                        return IndexedScreen.this.createIndexHandler(val);
                    }

                    @Override
                    public void setGlobal(T config) {
                        IndexedScreen.this.setGlobal(config);
                    }

                    @Override
                    protected W createSelectingDisplayWidget(T val) {
                        return IndexedScreen.this.createSelectingDisplayWidget(val);
                    }

                    @Override
                    public T getGlobal() {
                        return IndexedScreen.this.getGlobal();
                    }

                    @Override
                    public void saveSelected() {
                        IndexedScreen.this.saveSelected();
                    }
                };
    }

    protected int configButtonWidth = 100;
    protected int buttonHeight = 20;

    protected void onIndexChange() {
        // resize(this.client, this.width, this.height);
        if (subScreenDelegate != null) {
            subScreenDelegate.selectIndexToDisplay(getGlobal(), false);
        }
    }

    public abstract void setGlobal(T config);

    public abstract T getGlobal();

    protected abstract ElementHandler createIndexHandler(T val);

    protected abstract W createSelectingDisplayWidget(T val);

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        saveSelected();
        createDelegate();
        addRenderableWidget(this.subScreenDelegate);
    }

    public void resize(int width, int height) {
        saveSelected();
        super.resize(width, height);
    }

    public void onClose() {
        super.onClose();
        saveSelected();
    }
    //    @Override
    public abstract void saveSelected();
}
