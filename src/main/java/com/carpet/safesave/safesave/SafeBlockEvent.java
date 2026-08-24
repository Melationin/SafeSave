package com.carpet.safesave.safesave;

import net.minecraft.nbt.CompoundTag;

/**
 * 一条已排队的方块事件，为无损恢复而捕获。
 *
 * <p>原版将这些保存在 {@code ServerLevel.blockEvents} 中，并且<strong>根本不持久化</strong>——
 * 重启会悄然丢弃所有进行中的方块事件（一个已排队 {@code TRIGGER_EXTEND} 但尚未执行的活塞会直接忘掉）。
 *
 * <p>恢复时必须尊重原版容器的两个特性：
 * <ul>
 *   <li>它是 {@code ObjectLinkedOpenHashSet}，因此是<em>有序</em>的——
 *       {@code runBlockEvents} 用 {@code removeFirst()} 按插入顺序取出；</li>
 *   <li>它是<em>集合</em>，因此相同的 {@code (pos, block, paramA, paramB)} 不能出现两次。</li>
 * </ul>
 * 存储有序的 NBT 列表并按序重新加入即可精确复现这两点。
 *
 * @param blockId {@code BlockEventData.block()} 的注册表 id
 * @param paramA  {@code BlockEventData.paramA()}——对活塞：0 伸出，1 收回，2 掉落
 * @param paramB  {@code BlockEventData.paramB()}——对活塞：{@code Direction.get3DDataValue()}
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
     * @return 解析出的事件；当条目格式错误（id 缺失/为空）时为 {@code null}
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
