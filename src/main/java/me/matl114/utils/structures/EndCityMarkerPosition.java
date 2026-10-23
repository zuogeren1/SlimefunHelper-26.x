package me.matl114.utils.structures;

import net.minecraft.core.BlockPos;

public record EndCityMarkerPosition(
        EndCityMarker.Type type, String template, BlockPos position, boolean generated, String lootTable) {
    public boolean isChest() {
        return type == EndCityMarker.Type.CHEST;
    }

    public boolean isElytra() {
        return type == EndCityMarker.Type.ELYTRA;
    }
}
