package me.matl114.events.model;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import lombok.AllArgsConstructor;
import lombok.With;
import me.matl114.accessors.events.ItemRenderStateAccess;
import me.matl114.events.RenderListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

public interface GuiModel {
    public static final GuiModel EMPTY = new BlankGuiModel();

    @Nonnull
    public static GuiModel packOrder(List<GuiModel> guiModelList) {
        if (guiModelList == null || guiModelList.isEmpty()) {
            return EMPTY;
        }
        guiModelList =
                guiModelList.stream().filter(s -> !(s instanceof BlankGuiModel)).toList();
        if (guiModelList.isEmpty()) {
            return EMPTY;
        }
        if (guiModelList.size() == 1) {
            return guiModelList.get(0);
        }

        // todo
        return new PackingModel(guiModelList);
    }

    public static GuiModel of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return EMPTY;
        }
        return new ItemStackModel(stack);
    }

    public static GuiModel of(ItemModel model) {
        if (model == null) return EMPTY;
        return new ItemGuiModel(model);
    }

    public static GuiModel of(Identifier id) {
        if (id == null) return EMPTY;
        return of(RenderListener.getCustomModelOf(id));
    }

    default void update(
            ItemStackRenderState renderState,
            ItemStack stack,
            ItemModelResolver resolver,
            ItemDisplayContext displayContext,
            @Nullable ClientLevel world,
            @Nullable ItemOwner heldItemContext,
            int seed) {
        Entry entry = updateAndSubmit(renderState, stack, resolver, displayContext, world, heldItemContext, seed);
        if (entry != null) {
            var lst = ItemRenderStateAccess.of(renderState).getAttachedRenderState();
            lst.clear();
            lst.add(entry);
        } else {
            ItemRenderStateAccess.of(renderState).clearAttachedRenderState();
        }
    }

    // do not call
    public Entry updateAndSubmit(
            ItemStackRenderState renderState,
            ItemStack stack,
            ItemModelResolver resolver,
            ItemDisplayContext displayContext,
            @Nullable ClientLevel world,
            @Nullable ItemOwner heldItemContext,
            int seed);

    public static class BlankGuiModel implements GuiModel {
        @Override
        public Entry updateAndSubmit(
                ItemStackRenderState renderState,
                ItemStack stack,
                ItemModelResolver resolver,
                ItemDisplayContext displayContext,
                @Nullable ClientLevel world,
                @Nullable ItemOwner heldItemContext,
                int seed) {
            return null;
        }
    }

    @AllArgsConstructor
    public static class ItemStackModel implements GuiModel {
        ItemStack itemStack;

        @Override
        public Entry updateAndSubmit(
                ItemStackRenderState renderState,
                ItemStack stack,
                ItemModelResolver resolver,
                ItemDisplayContext displayContext,
                @Nullable ClientLevel world,
                @Nullable ItemOwner heldItemContext,
                int seed) {
            if (itemStack != null && !itemStack.isEmpty()) {
                ItemStackRenderState state2 = new ItemStackRenderState();

                // init attached info
                resolver.updateForTopItem(state2, itemStack, displayContext, world, heldItemContext, seed);
                return new Entry(null, state2);
            }
            return null;
        }
    }

    @AllArgsConstructor
    public static class ItemGuiModel implements GuiModel {
        ItemModel itemModel;

        @Override
        public Entry updateAndSubmit(
                ItemStackRenderState renderState,
                ItemStack stack,
                ItemModelResolver resolver,
                ItemDisplayContext displayContext,
                @Nullable ClientLevel world,
                @Nullable ItemOwner heldItemContext,
                int seed) {
            if (itemModel == null) {
                return null;
            }

            ItemStackRenderState state2 = new ItemStackRenderState();
            state2.clear();
            ItemRenderStateAccess.of(state2).setDisplayContext(displayContext);
            state2.setOversizedInGui(false);
            itemModel.update(
                    state2, new ItemStack(Items.BARRIER), resolver, displayContext, world, heldItemContext, seed);
            return new Entry(null, state2);
        }
    }

    @AllArgsConstructor
    public static class PackingModel implements GuiModel {
        List<GuiModel> guiModelList;

        @Override
        public void update(
                ItemStackRenderState renderState,
                ItemStack stack,
                ItemModelResolver resolver,
                ItemDisplayContext displayContext,
                @Nullable ClientLevel world,
                @Nullable ItemOwner heldItemContext,
                int seed) {
            List<Entry> updatedList =
                    updateAndSubmitList(renderState, stack, resolver, displayContext, world, heldItemContext, seed);
            if (updatedList != null && !updatedList.isEmpty()) {
                // arrange positions
                List<Entry> arranged = arrangeEntries(updatedList);
                var curr = ItemRenderStateAccess.of(renderState).getAttachedRenderState();
                curr.clear();
                curr.addAll(arranged);
            } else {
                ItemRenderStateAccess.of(renderState).clearAttachedRenderState();
            }
        }

        public List<Entry> updateAndSubmitList(
                ItemStackRenderState renderState,
                ItemStack stack,
                ItemModelResolver resolver,
                ItemDisplayContext displayContext,
                @Nullable ClientLevel world,
                @Nullable ItemOwner heldItemContext,
                int seed) {
            return guiModelList.stream()
                    .flatMap(s -> {
                        if (s instanceof PackingModel pack) {
                            return pack
                                    .updateAndSubmitList(
                                            renderState, stack, resolver, displayContext, world, heldItemContext, seed)
                                    .stream();
                        } else {
                            return Stream.of(s.updateAndSubmit(
                                    renderState, stack, resolver, displayContext, world, heldItemContext, seed));
                        }
                    })
                    .toList();
        }

        @Override
        public Entry updateAndSubmit(
                ItemStackRenderState renderState,
                ItemStack stack,
                ItemModelResolver resolver,
                ItemDisplayContext displayContext,
                @Nullable ClientLevel world,
                @Nullable ItemOwner heldItemContext,
                int seed) {
            throw new UnsupportedOperationException("DO NOT CALL");
        }

        private List<Entry> arrangeEntries(List<Entry> originalEntries) {
            List<Entry> result = new ArrayList<>();
            int count = Math.min(originalEntries.size(), 4);
            // 固定偏移量 (dx, dy) 对应四个位置
            float[][] offsets = {
                {0, 0}, // 第0个: 右下角
                {-1, 0}, // 第1个: 左下角
                {0, -1}, // 第2个: 右上角
                {-1, -1} // 第3个: 左上角
            };
            for (int i = 0; i < count; i++) {
                Entry entry = originalEntries.get(i);
                float dx = offsets[i][0];
                float dy = -offsets[i][1];
                // 平移变换器（注意：GUI中Y轴向下为正，向上为负，所以dy为负时向上移动）
                UnaryOperator<PoseStack> translator = matrices -> {
                    matrices.translate(dx, dy, 0);
                    return matrices;
                };
                UnaryOperator<PoseStack> combined = entry.stackTransformer() != null
                        ? matrices -> entry.stackTransformer().apply(translator.apply(matrices))
                        : translator;
                result.add(entry.withStackTransformer(combined));
            }
            return result;
        }
    }

    @With
    public static record Entry(@Nullable UnaryOperator<PoseStack> stackTransformer, ItemStackRenderState state) {}
}
