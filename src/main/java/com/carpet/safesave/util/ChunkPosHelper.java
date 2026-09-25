package com.carpet.safesave.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public final class ChunkPosHelper {
    private ChunkPosHelper() {
    }

    public static long pack(int x, int z) {
        //? if <26.1 {
        /*return ChunkPos.asLong(x, z);
        *///?} else {
        return ChunkPos.pack(x, z);
        //?}
    }

    public static long pack(BlockPos pos) {
        //? if <26.1 {
        /*return ChunkPos.asLong(pos);
        *///?} else {
        return ChunkPos.pack(pos);
        //?}
    }

    public static long pack(ChunkPos pos) {
        //? if <26.1 {
        /*return pos.toLong();
        *///?} else {
        return pos.pack();
        //?}
    }

    public static ChunkPos unpack(long key) {
        //? if <26.1 {
        /*return new ChunkPos(key);
        *///?} else {
        return ChunkPos.unpack(key);
        //?}
    }
}
