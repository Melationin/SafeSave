package com.carpet.safesave.safesave;

import net.minecraft.nbt.CompoundTag;

/**
 * 一条计划刻，以<em>绝对</em>时间捕获，因此重启无法扰动它。
 *
 * <p>原版将 {@code SavedTick(type, pos, int delay, priority)} 存在区块 NBT 中，并丢弃
 * {@code subTickOrder}。加载时它按 <em>区块开始方块刻时的游戏时间</em>重新锚定 delay，
 * 并<em>按区块</em>把 {@code subTickOrder} 重新编号为 {@code -N..-1}。这丢失了 (a) 绝对触发时间
 * 和 (b) 区块间的全局顺序。
 *
 * <p>因此本记录保留了原版丢弃的两个字段：
 * <ul>
 *   <li>{@link #triggerTick()} —— 该刻触发的绝对游戏时间，而非延迟；</li>
 *   <li>{@link #subTickOrder()} —— 原始的全局插入计数器。</li>
 * </ul>
 *
 * @param typeId       {@code Block}/{@code Fluid} 载荷的注册表 id
 * @param x            方块的 x
 * @param y            方块的 y
 * @param z            方块的 z
 * @param triggerTick  该刻触发的绝对游戏时间
 * @param priority     {@code TickPriority.getValue()}（-3..3）
 * @param subTickOrder 原始全局 {@code Level.subTickCount} 值
 */
public record SafeTick(String typeId, int x, int y, int z, long triggerTick, int priority, long subTickOrder) {

    private static final String KEY_ID = "i";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";
    /** 绝对触发刻——本功能的核心所在 */
    private static final String KEY_TRIGGER = "tt";
    private static final String KEY_PRIORITY = "p";
    /** 全局子刻顺序——原版丢失的另一项 */
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
    public static SafeTick load(final CompoundTag tag) {
        String id = tag.getStringOr(KEY_ID, "");
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
