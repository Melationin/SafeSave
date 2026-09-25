package com.carpet.safesave.util;

//? if <1.21.5 {
/*import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Optional;

*/
//?}

/**
 * Gives {@code CompoundTag} the convenience API that 1.21.5 added to it natively, on 1.21.4 and
 * older.
 *
 * <p>1.21.5 grew {@code getIntOr} / {@code getStringOr} / {@code getListOrEmpty} / {@code store} /
 * {@code read} on {@code CompoundTag}; before that only the raw accessors existed. Rather than fork
 * every call site, this interface is injected into {@code CompoundTag} through the access widener on
 * those versions only, so the same {@code tag.getIntOr(...)} source compiles everywhere.
 *
 * <p>1.21.5+ never loads it: {@code CompoundTag} already has all of these, and a mixin interface
 * whose default methods are shadowed by real class methods would only add confusion.
 *
 * <p>The base methods below are declared so the defaults can call them; {@code CompoundTag} already
 * implements every one of them.
 */
//? if <1.21.5 {
/*public interface TagCompatAccess {

    Tag get(String key);

    Tag put(String key, Tag value);

    boolean contains(String key, int type);

    int getInt(String key);

    long getLong(String key);

    float getFloat(String key);

    boolean getBoolean(String key);

    String getString(String key);

    CompoundTag getCompound(String key);

    ListTag getList(String key, int type);

    default int getIntOr(final String key, final int fallback) {
        Tag tag = this.get(key);
        return tag instanceof NumericTag numeric ? numeric.getAsInt() : fallback;
    }

    default long getLongOr(final String key, final long fallback) {
        Tag tag = this.get(key);
        return tag instanceof NumericTag numeric ? numeric.getAsLong() : fallback;
    }

    default float getFloatOr(final String key, final float fallback) {
        Tag tag = this.get(key);
        return tag instanceof NumericTag numeric ? numeric.getAsFloat() : fallback;
    }

    default boolean getBooleanOr(final String key, final boolean fallback) {
        Tag tag = this.get(key);
        return tag instanceof NumericTag numeric ? numeric.getAsByte() != 0 : fallback;
    }

    default String getStringOr(final String key, final String fallback) {
        Tag tag = this.get(key);
        return tag instanceof StringTag string ? string.getAsString() : fallback;
    }

    default ListTag getListOrEmpty(final String key) {
        Tag tag = this.get(key);
        return tag instanceof ListTag list ? list : new ListTag();
    }

    default <T> void store(final String key, final Codec<T> codec, final T value) {
        codec.encodeStart(NbtOps.INSTANCE, value).ifSuccess(tag -> this.put(key, tag));
    }

    default <T> Optional<T> read(final String key, final Codec<T> codec) {
        Tag tag = this.get(key);
        return tag == null ? Optional.empty() : codec.parse(NbtOps.INSTANCE, tag).result();
    }
}
*///?}
