package me.matl114.events.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

@AllArgsConstructor
@Getter
@Setter
@Accessors(fluent = true, chain = true)
public class PosUpdate {
    Vec3 oldPos;
    Vec3 newPos;
    Entity entity;
}
