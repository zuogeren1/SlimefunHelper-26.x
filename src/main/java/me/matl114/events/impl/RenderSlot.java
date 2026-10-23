package me.matl114.events.impl;

import me.matl114.versioned.api.VDrawContext;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

public record RenderSlot(VDrawContext context, AbstractContainerScreen<?> screen, Slot slot, double mouseX, double mouseY) {}
