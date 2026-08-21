package com.example.carpet.safesave;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Runtime bridge used while an entity is being saved/loaded.
 *
 * <p>All SafeSave entity data lives in one top-level {@code SafeSave} child compound. Since
 * {@link ValueOutput#child(String)} replaces any existing child of that name, only
 * {@code Entity.saveWithoutId} HEAD is allowed to create it; subclass mixins then write into the
 * shared output through this accessor. The load direction mirrors this with a shared input view.
 */
public interface SafeSaveEntityAccess {

    /** Non-null only between {@code Entity.saveWithoutId} HEAD and TAIL. */
    ValueOutput carpetExample$safeSaveOutput();

    /** Non-null only between {@code Entity.load} HEAD and TAIL. */
    ValueInput carpetExample$safeSaveInput();
}
