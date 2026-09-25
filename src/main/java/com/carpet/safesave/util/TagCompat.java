package com.carpet.safesave.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Optional;

// getString/getCompound/getLongArray 在 1.21.5 改了返回类型（裸值 -> Optional），注入接口救不了，
// 这里做一层转发让调用点与版本无关。
public final class TagCompat {
    private TagCompat() {
    }

    public static Optional<CompoundTag> compound(final CompoundTag tag, final String key) {
        //? if >=1.21.5 {
        return tag.getCompound(key);
        //?} else {
        /*return tag.contains(key, Tag.TAG_COMPOUND)
                ? Optional.of(tag.getCompound(key))
                : Optional.empty();
        *///?}
    }

    public static Optional<CompoundTag> compound(final ListTag list, final int index) {
        //? if >=1.21.5 {
        return list.getCompound(index);
        //?} else {
        /*Tag tag = index >= 0 && index < list.size() ? list.get(index) : null;
        return tag instanceof CompoundTag compound ? Optional.of(compound) : Optional.empty();
        *///?}
    }

    public static Optional<String> string(final CompoundTag tag, final String key) {
        //? if >=1.21.5 {
        return tag.getString(key);
        //?} else {
        /*return tag.contains(key, Tag.TAG_STRING)
                ? Optional.of(tag.getString(key))
                : Optional.empty();
        *///?}
    }

    // 必须是活子标签而非拷贝：调用方会就地写入。
    public static CompoundTag childOrNull(final CompoundTag tag, final String key) {
        //? if >=1.21.5 {
        return tag.getCompound(key).orElse(null);
        //?} else {
        /*return tag.contains(key, Tag.TAG_COMPOUND) ? tag.getCompound(key) : null;
        *///?}
    }

    public static long[] longArrayOrEmpty(final CompoundTag tag, final String key) {
        //? if >=1.21.5 {
        return tag.getLongArray(key).orElseGet(() -> new long[0]);
        //?} else {
        /*return tag.contains(key, Tag.TAG_LONG_ARRAY) ? tag.getLongArray(key) : new long[0];
        *///?}
    }
}
