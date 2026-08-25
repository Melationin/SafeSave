package com.carpet.safesave.safesave.blockentity;

import net.minecraft.nbt.CompoundTag;

/**
 * 一条移动中的活塞（{@code PistonMovingBlockEntity}）的 safe-save 状态快照。
 *
 * <p>原版 PME 自身 NBT 已保存 {@code progress}（实际存 progressO）、方向、扩展状态等；
 * safe-save 额外保存修复 #2/#4/#5 所需的字段：正确的进度、上一帧进度、最后 tick 时刻，以及
 * 全局创建序号。这些字段随 {@code SafeSaveStore.ChunkSnapshot} 按区块保存，同时写入
 * PME NBT 的 {@code safeSave} 子节点作为冗余。
 *
 * @param x          {@code PistonMovingBlockEntity.getBlockPos().getX()}
 * @param y          {@code PistonMovingBlockEntity.getBlockPos().getY()}
 * @param z          {@code PistonMovingBlockEntity.getBlockPos().getZ()}
 * @param order      全局创建序号
 * @param progress   当前进度
 * @param progressO  上一帧进度
 * @param lastTicked 最后 tick 的 {@code gameTime}
 */
public record SafePiston(int x, int y, int z, long order, float progress, float progressO, long lastTicked) {

    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";
    private static final String KEY_ORDER = "o";
    private static final String KEY_PROGRESS = "p";
    private static final String KEY_PROGRESS_O = "po";
    private static final String KEY_LAST_TICKED = "lt";

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_X, this.x);
        tag.putInt(KEY_Y, this.y);
        tag.putInt(KEY_Z, this.z);
        tag.putLong(KEY_ORDER, this.order);
        tag.putFloat(KEY_PROGRESS, this.progress);
        tag.putFloat(KEY_PROGRESS_O, this.progressO);
        tag.putLong(KEY_LAST_TICKED, this.lastTicked);
        return tag;
    }

    public static SafePiston load(final CompoundTag tag) {
        return new SafePiston(
                tag.getIntOr(KEY_X, 0),
                tag.getIntOr(KEY_Y, 0),
                tag.getIntOr(KEY_Z, 0),
                tag.getLongOr(KEY_ORDER, Long.MIN_VALUE),
                tag.getFloatOr(KEY_PROGRESS, 0.0F),
                tag.getFloatOr(KEY_PROGRESS_O, tag.getFloatOr(KEY_PROGRESS, 0.0F)),
                tag.getLongOr(KEY_LAST_TICKED, 0L)
        );
    }
}
