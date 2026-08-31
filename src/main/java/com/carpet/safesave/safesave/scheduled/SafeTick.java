package com.carpet.safesave.safesave.scheduled;

import net.minecraft.nbt.CompoundTag;

/**
 * @param triggerTick  该刻触发的绝对游戏时间
 * @param subTickOrder 原始全局 {@code Level.subTickCount} 值
 */
public record SafeTick(String typeId, int x, int y, int z, long triggerTick, int priority, long subTickOrder) {

    private static final String KEY_ID = "i";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";
    private static final String KEY_TRIGGER = "tt";
    private static final String KEY_PRIORITY = "p";
    private static final String KEY_SUB_ORDER = "so";

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_ID, this.typeId);
        tag.putInt(KEY_X, this.x);
        tag.putInt(KEY_Y, this.y);
        tag.putInt(KEY_Z, this.z);
        tag.putLong(KEY_TRIGGER, this.triggerTick);
        tag.putInt(KEY_PRIORITY, this.priority);
        tag.putLong(KEY_SUB_ORDER, this.subTickOrder);
        return tag;
    }

    /**
     * @return 解析出的刻；当条目格式错误（id 缺失/为空）时为 {@code null}
     */
    public static SafeTick load(final CompoundTag tag) {        String id = tag.getStringOr(KEY_ID, "");
        if (id.isEmpty()) {
            return null;
        }
        return new SafeTick(
                id,
                tag.getIntOr(KEY_X, 0),
                tag.getIntOr(KEY_Y, 0),
                tag.getIntOr(KEY_Z, 0),
                tag.getLongOr(KEY_TRIGGER, 0L),
                tag.getIntOr(KEY_PRIORITY, 0),
                tag.getLongOr(KEY_SUB_ORDER, 0L)
        );
    }
}
