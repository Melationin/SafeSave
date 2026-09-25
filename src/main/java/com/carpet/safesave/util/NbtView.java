package com.carpet.safesave.util;

import com.mojang.serialization.Codec;

import java.util.Optional;

//? if >=1.21.6 {
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?} else {
/*import net.minecraft.nbt.CompoundTag;
*///?}

// 1.21.6 才从 CompoundTag 抽出 ValueInput/ValueOutput，两者方法同名，因此一套视图即可通吃所有版本；
// 版本差异只剩 mixin 注入参数的类型。
public interface NbtView {

    interface Writer {
        void putInt(String key, int value);

        void putLong(String key, long value);

        void putFloat(String key, float value);

        void putBoolean(String key, boolean value);

        void putString(String key, String value);

        <T> void store(String key, Codec<T> codec, T value);

        Writer child(String key);
    }

    interface Reader {
        int getIntOr(String key, int fallback);

        long getLongOr(String key, long fallback);

        float getFloatOr(String key, float fallback);

        boolean getBooleanOr(String key, boolean fallback);

        Optional<String> getString(String key);

        <T> Optional<T> read(String key, Codec<T> codec);

        Optional<Reader> child(String key);
    }

    //? if >=1.21.6 {
    static Writer writer(final ValueOutput output) {
        return new ValueWriterView(output);
    }

    static Reader reader(final ValueInput input) {
        return new ValueReaderView(input);
    }
    //?} else {
    /*static Writer writer(final CompoundTag tag) {
        return new TagWriterView(tag);
    }

    static Reader reader(final CompoundTag tag) {
        return new TagReaderView(tag);
    }
    *///?}
}


//? if >=1.21.6 {
final class ValueWriterView implements NbtView.Writer {
    private final ValueOutput output;

    ValueWriterView(final ValueOutput output) {
        this.output = output;
    }

    @Override
    public void putInt(final String key, final int value) {
        this.output.putInt(key, value);
    }

    @Override
    public void putLong(final String key, final long value) {
        this.output.putLong(key, value);
    }

    @Override
    public void putFloat(final String key, final float value) {
        this.output.putFloat(key, value);
    }

    @Override
    public void putBoolean(final String key, final boolean value) {
        this.output.putBoolean(key, value);
    }

    @Override
    public void putString(final String key, final String value) {
        this.output.putString(key, value);
    }

    @Override
    public <T> void store(final String key, final Codec<T> codec, final T value) {
        this.output.store(key, codec, value);
    }

    // TagValueOutput#child 每次都新建 tag 并覆盖父槽，而 saveWithoutId 与 addAdditionalSaveData 都往
    // "safeSave" 写，直接 child() 会把另一个 hook 写的内容冲掉。这是 ValueOutputAccess 存在的唯一原因。
    @Override
    public NbtView.Writer child(final String key) {
        if (this.output instanceof TagValueOutput tagOutput) {
            ValueOutput existing = tagOutput.getChild(key);
            if (existing != null) {
                return new ValueWriterView(existing);
            }
            return new ValueWriterView(tagOutput.child(key));
        }
        return new ValueWriterView(this.output.child(key));
    }
}


final class ValueReaderView implements NbtView.Reader {
    private final ValueInput input;

    ValueReaderView(final ValueInput input) {
        this.input = input;
    }

    @Override
    public int getIntOr(final String key, final int fallback) {
        return this.input.getIntOr(key, fallback);
    }

    @Override
    public long getLongOr(final String key, final long fallback) {
        return this.input.getLongOr(key, fallback);
    }

    @Override
    public float getFloatOr(final String key, final float fallback) {
        return this.input.getFloatOr(key, fallback);
    }

    @Override
    public boolean getBooleanOr(final String key, final boolean fallback) {
        return this.input.getBooleanOr(key, fallback);
    }

    @Override
    public Optional<String> getString(final String key) {
        return this.input.getString(key);
    }

    @Override
    public <T> Optional<T> read(final String key, final Codec<T> codec) {
        return this.input.read(key, codec);
    }

    @Override
    public Optional<NbtView.Reader> child(final String key) {
        return this.input.child(key).map(ValueReaderView::new);
    }
}
//?} else {
/*final class TagWriterView implements NbtView.Writer {
    private final CompoundTag tag;

    TagWriterView(final CompoundTag tag) {
        this.tag = tag;
    }

    private static CompoundTag liveChild(final CompoundTag tag, final String key) {
        CompoundTag existing = TagCompat.childOrNull(tag, key);
        if (existing != null) {
            return existing;
        }
        CompoundTag created = new CompoundTag();
        tag.put(key, created);
        return created;
    }

    @Override
    public void putInt(final String key, final int value) {
        this.tag.putInt(key, value);
    }

    @Override
    public void putLong(final String key, final long value) {
        this.tag.putLong(key, value);
    }

    @Override
    public void putFloat(final String key, final float value) {
        this.tag.putFloat(key, value);
    }

    @Override
    public void putBoolean(final String key, final boolean value) {
        this.tag.putBoolean(key, value);
    }

    @Override
    public void putString(final String key, final String value) {
        this.tag.putString(key, value);
    }

    @Override
    public <T> void store(final String key, final Codec<T> codec, final T value) {
        this.tag.store(key, codec, value);
    }

    @Override
    public NbtView.Writer child(final String key) {
        return new TagWriterView(liveChild(this.tag, key));
    }
}


final class TagReaderView implements NbtView.Reader {
    private final CompoundTag tag;

    TagReaderView(final CompoundTag tag) {
        this.tag = tag;
    }

    @Override
    public int getIntOr(final String key, final int fallback) {
        return this.tag.getIntOr(key, fallback);
    }

    @Override
    public long getLongOr(final String key, final long fallback) {
        return this.tag.getLongOr(key, fallback);
    }

    @Override
    public float getFloatOr(final String key, final float fallback) {
        return this.tag.getFloatOr(key, fallback);
    }

    @Override
    public boolean getBooleanOr(final String key, final boolean fallback) {
        return this.tag.getBooleanOr(key, fallback);
    }

    @Override
    public Optional<String> getString(final String key) {
        return TagCompat.string(this.tag, key);
    }

    @Override
    public <T> Optional<T> read(final String key, final Codec<T> codec) {
        return this.tag.read(key, codec);
    }

    @Override
    public Optional<NbtView.Reader> child(final String key) {
        return TagCompat.compound(this.tag, key).map(TagReaderView::new);
    }
}
*///?}
