package me.matl114.events.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

@Accessors(fluent = true)
@Setter
@Getter
@AllArgsConstructor
public class BlockBreak implements SequencedAction {
    BlockPos blockPos;
    Stage stage;
    ItemStack stack;
    float progress;

    public enum Stage {
        PRE,
        POST;
    }
}
