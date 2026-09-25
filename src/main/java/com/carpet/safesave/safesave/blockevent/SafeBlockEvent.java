package com.carpet.safesave.safesave.blockevent;

import net.minecraft.nbt.CompoundTag;

/*
 * 一条已排队的方块事件，为无损恢复而捕获。
 * order：全局递增序号
 */
public record SafeBlockEvent(String blockId, int x, int y, int z, int paramA, int paramB, long order) {

    private static final String KEY_ID = "i";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";
    private static final String KEY_PARAM_A = "a";
    private static final String KEY_PARAM_B = "b";
    private static final String KEY_ORDER = "o";

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_ID, this.blockId);
        tag.putInt(KEY_X, this.x);
        tag.putInt(KEY_Y, this.y);
        tag.putInt(KEY_Z, this.z);
        tag.putInt(KEY_PARAM_A, this.paramA);
        tag.putInt(KEY_PARAM_B, this.paramB);
        tag.putLong(KEY_ORDER, this.order);
        return tag;
    }

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
                tag.getIntOr(KEY_PARAM_B, 0),
                tag.getLongOr(KEY_ORDER, -1L)
        );
    }
}
