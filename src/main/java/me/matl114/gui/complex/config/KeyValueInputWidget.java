package me.matl114.gui.complex.config;

import java.util.List;
import me.matl114.gui.basic.*;
import me.matl114.gui.interfaces.DefaultElementFactory;
import me.matl114.gui.interfaces.ElementFactory;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.config.AttrKeyValue;
import net.minecraft.network.chat.Component;

public class KeyValueInputWidget<T> extends SubScreenWidget {
    AttrKeyValue<T> keyValueHolder;
    protected int dkey;
    protected int dblank;
    protected int dvalue;
    protected final ElementFactory elementFactory;

    public KeyValueInputWidget(int x, int y, int dx, int dy, int dKey, AttrKeyValue<T> kv) {
        this(x, y, dx, dy, dKey, 0, dx - dKey, kv, DefaultElementFactory.INSTANCE);
    }

    public KeyValueInputWidget(int x, int y, int dx, int dy, int dKey, int dblank, int dvalue, AttrKeyValue<T> kv) {
        this(x, y, dx, dy, dKey, dblank, dvalue, kv, DefaultElementFactory.INSTANCE);
    }

    public KeyValueInputWidget(
            int x,
            int y,
            int dx,
            int dy,
            int dKey,
            int dblank,
            int dvalue,
            AttrKeyValue<T> kv,
            ElementFactory elementFactory) {
        super(x, y, dx, dy);
        this.dkey = dKey;
        this.dblank = dblank;
        this.dvalue = dvalue;
        this.keyValueHolder = kv;
        this.elementFactory = elementFactory;
        init();
    }

    List<Component> cachedTooltips;

    public KeyValueInputWidget<T> setTooltips(List<Component> tooltips) {
        this.cachedTooltips = tooltips;
        return this;
    }

    public Component getTranslationName() {
        String key = getKeyName();
        return Component.translatableWithFallback(key, key);
    }

    public List<Component> getTooltips() {
        if (cachedTooltips == null) {
            cachedTooltips = ChatUtils.parseTooltipsTranslation(this.keyValueHolder.getKeyName() + ".tooltips", "暂无介绍");
        }
        return cachedTooltips;
    }

    DrawableWidget keyLabel;
    DrawableWidget interactPlace;

    protected void valueChange() {}

    public String getKeyName() {
        return keyValueHolder.getKeyName();
    }

    public DrawableWidget createKeyLabel() {
        return elementFactory.createLabel(this::getTranslationName, this::getTooltips, 0, 0, dkey, dy);
    }

    protected void init() {

        this.keyLabel = createKeyLabel().addToSub(this);
        this.interactPlace = this.keyValueHolder
                .generateValueWidget(elementFactory, dkey + dblank, 0, dvalue, dy)
                .addToSub(this);
    }
}
