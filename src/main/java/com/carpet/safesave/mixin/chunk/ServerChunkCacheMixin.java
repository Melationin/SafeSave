package com.carpet.safesave.mixin.chunk;

import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.carpet.safesave.safesave.region.RegionLifecycle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.entity.MobCategory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Shadow @Final private ServerLevel level;

    // Internal getBlockState/getBlockEntity calls otherwise keep refreshing UNKNOWN tickets
    // under our own region ticket, turning an active machine into a permanent chunk loader.
    @WrapOperation(method = "getChunkFutureMainThread", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;addTicket(Lnet/minecraft/server/level/Ticket;Lnet/minecraft/world/level/ChunkPos;)V"))
    private void SS$avoidSelfRetention(ServerChunkCache source, Ticket ticket, ChunkPos pos, Operation<Void> original) {
        if (ticket.getType() == TicketType.UNKNOWN && RegionLifecycle.coveredByRegionTicket(this.level, pos.pack())) return;
        original.call(source, ticket, pos);
    }

    @Inject(method = "tickSpawningChunk", at = @At("HEAD"), cancellable = true)
    private void SS$gateSpawning(LevelChunk chunk, long elapsed, List<MobCategory> categories,
                                NaturalSpawner.SpawnState state, CallbackInfo ci) {
        if (!RegionLifecycle.maySimulate(this.level, chunk.getPos().pack())) ci.cancel();
    }
}
