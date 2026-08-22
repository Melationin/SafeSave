package com.carpet.safesave.safesave;

import net.minecraft.nbt.CompoundTag;

/**
 * One scheduled tick, captured with <em>absolute</em> timing so that a restart cannot perturb it.
 *
 * <p>Vanilla stores {@code SavedTick(type, pos, int delay, priority)} inside the chunk NBT and
 * throws {@code subTickOrder} away. On load it re-anchors the delay against
 * <em>the game time at which the chunk starts block-ticking</em>, and re-numbers {@code subTickOrder}
 * as {@code -N..-1} <em>per chunk</em>. That loses (a) the absolute trigger time and (b) the global
 * ordering between chunks.
 *
 * <p>This record therefore keeps the two fields vanilla drops:
 * <ul>
 *   <li>{@link #triggerTick()} — the absolute game time the tick fires at, not a delay;</li>
 *   <li>{@link #subTickOrder()} — the original global insertion counter.</li>
 * </ul>
 *
 * @param typeId       registry id of the {@code Block}/{@code Fluid} payload
 * @param x            block x
 * @param y            block y
 * @param z            block z
 * @param triggerTick  absolute game time at which this tick fires
 * @param priority     {@code TickPriority.getValue()} (-3..3)
 * @param subTickOrder original global {@code Level.subTickCount} value
 */
public record SafeTick(String typeId, int x, int y, int z, long triggerTick, int priority, long subTickOrder) {

    private static final String KEY_ID = "i";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";
    /** absolute trigger tick — the whole point of this feature */
    private static final String KEY_TRIGGER = "tt";
    private static final String KEY_PRIORITY = "p";
    /** global sub-tick order — the other thing vanilla loses */
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
     * @return the parsed tick, or {@code null} when the entry is malformed (missing/blank id)
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
