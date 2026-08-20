package com.example.carpet.safesave;

import net.minecraft.nbt.CompoundTag;

/**
 * One queued block event, captured for lossless restore.
 *
 * <p>Vanilla keeps these in {@code ServerLevel.blockEvents} and <strong>never persists them at
 * all</strong> — a restart silently discards every in-flight block event (a piston that had queued
 * {@code TRIGGER_EXTEND} but not yet executed it simply forgets).
 *
 * <p>Two properties of the vanilla container must be respected when restoring:
 * <ul>
 *   <li>it is an {@code ObjectLinkedOpenHashSet}, so it is <em>ordered</em> —
 *       {@code runBlockEvents} drains it with {@code removeFirst()} in insertion order;</li>
 *   <li>it is a <em>set</em>, so an identical {@code (pos, block, paramA, paramB)} cannot appear
 *       twice.</li>
 * </ul>
 * Storing an ordered NBT list and re-adding in order reproduces both exactly.
 *
 * @param blockId registry id of {@code BlockEventData.block()}
 * @param paramA  {@code BlockEventData.paramA()} — for pistons: 0 extend, 1 contract, 2 drop
 * @param paramB  {@code BlockEventData.paramB()} — for pistons: {@code Direction.get3DDataValue()}
 */
public record SafeBlockEvent(String blockId, int x, int y, int z, int paramA, int paramB) {

    private static final String KEY_ID = "i";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";
    private static final String KEY_PARAM_A = "a";
    private static final String KEY_PARAM_B = "b";

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_ID, this.blockId);
        tag.putInt(KEY_X, this.x);
        tag.putInt(KEY_Y, this.y);
        tag.putInt(KEY_Z, this.z);
        tag.putInt(KEY_PARAM_A, this.paramA);
        tag.putInt(KEY_PARAM_B, this.paramB);
        return tag;
    }

    /**
     * @return the parsed event, or {@code null} when the entry is malformed (missing/blank id)
     */
    public static SafeBlockEvent load(final CompoundTag tag) {
        String id = tag.getStringOr(KEY_ID, "");
        if (id.isEmpty()) {
            return null;
        }
        return new SafeBlockEvent(
                id,
                tag.getIntOr(KEY_X, 0),
                tag.getIntOr(KEY_Y, 0),
                tag.getIntOr(KEY_Z, 0),
                tag.getIntOr(KEY_PARAM_A, 0),
                tag.getIntOr(KEY_PARAM_B, 0)
        );
    }
}
