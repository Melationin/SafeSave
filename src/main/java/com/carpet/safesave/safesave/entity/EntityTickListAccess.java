package com.carpet.safesave.safesave.entity;

import net.minecraft.world.entity.Entity;

import java.util.List;

public interface EntityTickListAccess {

    List<Entity> SS$snapshotActive();

    void SS$rebuildActive(List<Entity> ordered);
}
