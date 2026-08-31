package com.carpet.safesave.safesave.blockevent;

import net.minecraft.nbt.CompoundTag;

/**
 * 一条已排队的方块事件，为无损恢复而捕获。
 *
 * <p>原版把事件保存在 {@code ServerLevel.blockEvents} 中且<strong>根本不持久化</strong>——
 * 重启会悄然丢弃所有进行中的方块事件（如已排队但未执行的活塞 {@code TRIGGER_EXTEND}）。
 * 该容器是有序集合：按插入顺序执行、相同 {@code (pos, block, paramA, paramB)} 不重复。
 *
 * @param paramA {@code BlockEventData.paramA()}——对活塞：0 伸出，1 收回，2 掉落
 * @param paramB {@code BlockEventData.paramB()}——对活塞：{@code Direction.get3DDataValue()}
 * @param order  全局递增序号
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

    /**
     * @return 解析出的事件；当条目格式错误（id 缺失/为空）时为 {@code null}。
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
                tag.getIntOr(KEY_PARAM_B, 0),
                tag.getLongOr(KEY_ORDER, -1L)
        );
    }
}
