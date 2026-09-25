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

// 1.21.5 给 CompoundTag 加了 getIntOr/getStringOr/getListOrEmpty/store/read；更早的版本只有原始
// 访问器，所以由这里补出来，并用 access widener 把接口注入 CompoundTag。1.21.5+ 既不需要也不加载。
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
