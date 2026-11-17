package me.zuogeren.slimefunhelperplus.mixin;

import java.util.List;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.gui.interfaces.ElementFactory;
import me.matl114.utils.config.AttrKeyValue;
import me.matl114.utils.config.KeyValue;
import me.zuogeren.slimefunhelperplus.hostgui.AttrKeyValueDropdownWidget;
import me.zuogeren.slimefunhelperplus.module.newclickgui.style.endfield.EndfieldModuleConfigScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让宿主配置页里「有候选值的字符串配置」显示成可编辑值框 + 候选箭头。
 *
 * <p>宿主对 {@code StringRef} 一律画文本框，只有 {@code EnumAttrKeyValue} 走下拉；
 * 而配置项必须经 {@code builder(...)} 登记，那条路固定产出 {@code StringRef}。
 * 所以模型文件名这类「只能从已有里选、又允许手填」的配置在宿主侧没有入口。
 *
 * <p>注入点是 {@code AttrKeyValue#generateValueWidget} —— 宿主所有值控件的唯一创建入口
 * （{@code KeyValueInputWidget#init} 调它）。没注册候选的配置原样放行，行为逐字节不变。
 *
 * <p>{@code require = 0}：宿主是上游代码，改名后这里会失效；失效时退回文本框，不该让游戏起不来。
 */
@Mixin(AttrKeyValue.class)
public interface AttrKeyValueDropdownMixin {

    @Inject(method = "generateValueWidget", at = @At("HEAD"), cancellable = true, require = 0)
    private void sfhplus$dropdownValueWidget(ElementFactory factory, int x, int y, int inputDx, int dy,
            CallbackInfoReturnable<DrawableWidget> callbackInfo) {
        // 不能用 @Shadow：getKeyName() 声明在父接口 KeyValue 上，shadow 只在目标接口自身的方法表里找
        String key = ((KeyValue<?>) this).getKeyName();
        List<String> choices = EndfieldModuleConfigScreen.stringChoicesOfPath(key);
        if (choices.isEmpty()) {
            return;
        }
        DrawableWidget widget = AttrKeyValueDropdownWidget.create(factory, (AttrKeyValue<?>) this, choices, x, y, inputDx, dy);
        if (widget == null) {
            return;
        }
        callbackInfo.setReturnValue(widget);
    }
}
