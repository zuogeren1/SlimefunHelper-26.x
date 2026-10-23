package me.matl114.mixins.events;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import me.matl114.accessors.events.ItemRenderStateAccess;
import me.matl114.events.model.GuiModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStackRenderState.class)
public abstract class ItemRenderStateEvents implements ItemRenderStateAccess {
    @Shadow
    ItemDisplayContext displayContext;

    @Shadow
    public abstract void appendModelIdentityElement(Object modelKey);

    @Shadow
    public abstract void clear();

    @Unique
    List<GuiModel.Entry> attachedRenders;

    public List<GuiModel.Entry> getAttachedRenderState() {
        if (attachedRenders == null) {
            attachedRenders = new ArrayList<>();
            appendModelIdentityElement(attachedRenders);
        }
        return attachedRenders;
    }

    public void clearAttachedRenderState() {
        if (attachedRenders != null) {
            attachedRenders.clear();
        }
    }

    @Override
    public void setDisplayContext(ItemDisplayContext displayContext) {
        this.displayContext = displayContext;
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void onRender1(
            PoseStack matrices,
            SubmitNodeCollector orderedRenderCommandQueue,
            int light,
            int overlay,
            int i,
            CallbackInfo ci) {
        if (attachedRenders != null && !attachedRenders.isEmpty()) {
            matrices.pushPose();
            try {
                final float scale = 0.54f;
                final float scale_ground = 0.8f;
                boolean inGui = false;
                var renderMode = this.displayContext;
                if (renderMode == ItemDisplayContext.GUI) {
                    inGui = true;
                    matrices.translate(0.26, -0.26, 1f);
                    matrices.scale(scale, scale, scale);
                } else if (renderMode == ItemDisplayContext.GROUND) {
                    matrices.translate(0.15, -0.15, 0);
                    matrices.scale(scale_ground, scale_ground, scale_ground);
                } else if (renderMode == ItemDisplayContext.FIXED) {
                    matrices.translate(-0.25, -0.25, -0.05);
                    matrices.scale(scale_ground, scale_ground, scale_ground);
                } else if (renderMode == ItemDisplayContext.HEAD) {
                    // seems too wierd, give up
                    return;
                    //                    matrices.translate(-0.25,0.5,-0.05);
                    //    //                matrices. scale(scale_ground, scale_ground, scale_ground);
                    //                    renderMode = ModelTransformationMode.FIXED;
                } else if (renderMode == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND) {
                    // seems too wierd
                    //                matrices.translate(0.25,0.25,0.05);
                    //               matrices. scale(scale, scale, scale);
                    //                renderMode = ModelTransformationMode.GUI;
                    return;
                } else if (renderMode == ItemDisplayContext.THIRD_PERSON_LEFT_HAND) {
                    // seems too wierd
                    //                matrices.translate(0.25,0.25,0.05);
                    //                matrices. scale(scale, scale, scale);
                    //                renderMode = ModelTransformationMode.GUI;
                    return;
                } else {
                    return;
                }
                if (inGui) {
                    Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_FLAT);
                }
                for (var entry : attachedRenders) {
                    if (entry.stackTransformer() != null) {
                        matrices.pushPose();
                        entry.stackTransformer().apply(matrices);
                        entry.state().submit(matrices, orderedRenderCommandQueue, light, overlay, i);
                        matrices.popPose();
                    } else {
                        entry.state().submit(matrices, orderedRenderCommandQueue, light, overlay, i);
                    }
                }
            } finally {
                matrices.popPose();
            }
        }
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void onClear(CallbackInfo ci) {
        clearAttachedRenderState();
    }
}
