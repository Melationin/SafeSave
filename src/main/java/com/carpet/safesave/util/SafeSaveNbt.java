package com.carpet.safesave.util;

import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;


public final class SafeSaveNbt {
    private SafeSaveNbt() {
    }
    public static final String KEY_SAFE_SAVE = "safeSave";
    public static boolean enabled() {
        return com.carpet.safesave.safesave.SafeSaveManager.enabled();
    }

    public static ValueOutput child(final ValueOutput output) {
        if (output instanceof TagValueOutput tagValueOutput) {
            ValueOutput existing = tagValueOutput.getChild(KEY_SAFE_SAVE);
            if (existing != null) {
                return existing;
            }
            return tagValueOutput.child(KEY_SAFE_SAVE);
        }
        return output.child(KEY_SAFE_SAVE);
    }

    public static ValueInput childOrNull(final ValueInput input) {
        return input.child(KEY_SAFE_SAVE).orElse(null);
    }
}
