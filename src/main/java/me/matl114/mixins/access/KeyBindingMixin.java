package me.matl114.mixins.access;

import com.mojang.blaze3d.platform.InputConstants;
import me.matl114.accessors.hacks.KeyBindAccess;
import me.matl114.managers.input.KeyCode;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(KeyMapping.class)
public abstract class KeyBindingMixin implements KeyBindAccess {
    @Shadow
    private InputConstants.Key key;

    public void resetKeyState() {
        int code = key.getValue();
        if (key.getType() == InputConstants.Type.MOUSE) setDown(KeyCode.isMouseButtonDown(code));
        else setDown(KeyCode.isKeyDown(code));
    }

    @Shadow
    public abstract void setDown(boolean b);
}
