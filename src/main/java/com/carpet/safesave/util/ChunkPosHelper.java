package com.carpet.safesave.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * The chunk-position packing API was renamed in 26.1:
 * <ul>
 *     <li>{@code ChunkPos.asLong(int, int)} / {@code asLong(BlockPos)} / {@code pos.toLong()}
 *     became {@code ChunkPos.pack(int, int)} / {@code pack(BlockPos)} / {@code pos.pack()}</li>
 *     <li>{@code new ChunkPos(long)} became {@code ChunkPos.unpack(long)}</li>
 * </ul>
 * Every call site goes through this helper so the difference lives in exactly one file.
 */
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
