package me.matl114.events.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

@Getter
@Setter
@Accessors(fluent = true)
@AllArgsConstructor
public class SlowMovement {
    Vec3 movementModifier;
    final BlockPos pos;
    final BlockState state;
}
