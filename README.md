# SafeSave

A Carpet extension for Minecraft **26.2** / **26.1** that makes **scheduled ticks (计划刻)** and
**block events (方块事件)** survive a server restart intact, and ships the debug instrumentation
needed to prove it.

Verified end-to-end on a real dev server for 26.2; every vanilla API it touches is identical in 26.1,
so the same source builds for both (stonecutter multi-version, no conditional compilation needed).

```
./gradlew build                     # -> versions/<ver>/build/libs/SafeSave-<ver>-<modver>.jar
./gradlew :26.2:runServer           # dev server
tools/setup-void-server.sh          # provision a void world first (recommended, see §7)
```

Requires a JDK 25 toolchain (`sourceCompatibility = 25`).

See **[`DESIGN.md`](DESIGN.md)** for the full change inventory: every injection point and why it was
chosen, the design decisions and rejected alternatives, the bugs found during development, and an
explicit list of what is and is not verified.

| | |
|---|---|
| mod id | `safesave` |
| carpet rule | `safeSave` (default `false`) |
| command | `/safesave` |
| data file | `<world>/safesave.dat` (format v3, reads v1/v2) |

---

## 1. What vanilla loses on restart

A chunk stores its ticks as `SavedTick(type, pos, int delay, priority)`. On load,
`LevelChunk.unpackTicks(gameTime)`:

* re-anchors `delay` against the game time at which **that chunk** starts block-ticking, so the
  absolute trigger time drifts by `T_unpack − T_save` for any chunk not loaded at startup;
* re-numbers `subTickOrder` as `-N..-1` **per chunk**, destroying the global ordering between chunks
  (mass ties, resolved by hash-map iteration order);
* `Level.subTickCount` is never persisted at all — it resets to `0`;
* scheduling a tick does **not** mark the chunk unsaved, so a chunk whose only change was a
  scheduled tick is never rewritten and the tick is silently lost;
* `ServerLevel.blockEvents` is **not persisted anywhere at all** — every in-flight block event is
  discarded on restart (a piston that queued `TRIGGER_EXTEND` but had not executed it simply
  forgets);
* and a **moving piston** loses fidelity four separate ways, see §8.

## 2. What this does

Keeps an authoritative side store in `<world>/safesave.dat` holding, per tick:

| field | meaning |
|---|---|
| `i` | registry id of the `Block`/`Fluid` payload |
| `x` `y` `z` | block position |
| `tt` | **absolute** `triggerTick` (not a delay) |
| `p`  | `TickPriority.getValue()` |
| `so` | **original global** `subTickOrder` |

and, per dimension, the **ordered** block-event queue:

| field | meaning |
|---|---|
| `i` | registry id of `BlockEventData.block()` |
| `x` `y` `z` | block position |
| `a` | `paramA` — pistons: 0 extend, 1 contract, 2 drop |
| `b` | `paramB` — pistons: `Direction.get3DDataValue()` |

plus per dimension `subTickCount`, and — **debug only, never read while restoring** —
`debug.serverTickCount` and per-level `gameTime`.

`ServerLevel.blockEvents` is an `ObjectLinkedOpenHashSet` drained with `removeFirst()`, so insertion
order *is* execution order; the file stores an ordered list and re-adds in order. Anything already
queued when the restore runs is re-appended *behind* the restored (older) events.
`blockEventsToReschedule` needs no saving: `runBlockEvents` puts everything it could not run back into
`blockEvents` before returning, so it never holds state across a tick boundary.

Being independent of vanilla's chunk NBT also sidesteps the `markUnsaved` loss entirely.

### Lifecycle

| when | what |
|---|---|
| `MinecraftServer.loadLevel` HEAD (Carpet `onServerLoaded`) | read the side file |
| `MinecraftServer.prepareLevels` HEAD | restore `Level.subTickCount` + the block-event queue, bind debug labels |
| `LevelChunk.unpackTicks` HEAD/TAIL | replace vanilla's re-anchored ticks with the absolute ones |
| `MinecraftServer.tickServer` HEAD (once) | **freeze** the server if there was data to restore |
| `ServerLevel.tick` HEAD (once per dimension) | sweep chunks already at `FULL` but not yet ticking |
| `ServerLevel.unload` HEAD | snapshot that chunk before its containers are unregistered |
| `MinecraftServer.saveAllChunks` HEAD | snapshot every loaded chunk, then write the file |

The restore queue (`pendingRestore`, populated only from on-disk data) is kept separate from the live
snapshot store, so a save landing between two restore paths cannot cause a chunk to be applied twice.
A chunk's snapshot is re-created when it unloads. Because trigger times are absolute, an entry for a long-unloaded chunk stays valid
indefinitely — nothing drifts.

### Freeze on startup

When there is data to restore the server is frozen before its first tick. While frozen
`TickRateManager.runsNormally()` is `false`, so `ServerLevel.tick` skips the
`blockTicks`/`fluidTicks` phases and `gameTime` does not move — the restored ticks sit untouched
until you run `/tick unfreeze`.

## 3. Usage

```
/carpet safeSave true      # then click [Change permanently?] — see the caveat below
/tick unfreeze             # after a restore, once you are happy with the state
```

> **Caveat that bites:** `/carpet safeSave true` is **session-only** unless you make it permanent.
> The rule is read at `loadLevel` HEAD, so if it is not in `<world>/carpet.conf` the next start sees
> `false` and restores nothing. Either click `[Change permanently?]`, or add
> `safeSave true` to `<world>/carpet.conf` directly.

## 4. Debug output

`DebugSwitches.DEBUG` is a `public static final boolean` compile-time constant (currently `true`).
Flip it to `false` and javac strips every guarded call out of the class files.

While it is `true`, channels are toggled at runtime:

```
/safesave                          show all channel states
/safesave scheduledTicks <bool>    scheduled tick add / dedup / execute
/safesave blockEvents <bool>       block event add / dedup / execute
/safesave worldTick <bool>         one line at the head of every ServerLevel.tick
/safesave all <bool>
/safesave status                  safe-save status and statistics
```

Sample output:

```
[ST][ADD  ] minecraft:overworld/block minecraft:observer (1,100,0) trigger=527 now=525 delay=2 prio=NORMAL(0) sub=4468
[ST][DEDUP] minecraft:overworld/fluid minecraft:water (15,13,2) trigger=137 now=132 delay=5 prio=NORMAL(0) sub=2264
[ST][RUN  ] minecraft:overworld/block minecraft:observer (1,100,0) trigger=386 now=525 late=139 prio=NORMAL(0) sub=3798
[BE][ADD  ] minecraft:overworld minecraft:piston (5,100,0) a=0 b=5 queue=1 gameTime=584
[BE][RUN  ] minecraft:overworld minecraft:piston (5,100,0) a=0 b=5 handled=true state=Block{minecraft:piston}[extended=true,facing=east]
[TICK] minecraft:overworld gameTime=128 serverTick=129 frozen=false blockTicks=10 fluidTicks=678 blockEventsPending=0
```

`ADD` vs `DEDUP` reproduces vanilla's own accept/drop decision, which is otherwise invisible:
scheduled ticks de-duplicate on `(type, pos)` (ignoring `triggerTick`/`priority`), and
`ServerLevel.blockEvents` is an `ObjectLinkedOpenHashSet`, so an identical
`BlockEventData(pos, block, paramA, paramB)` queued twice in one tick is dropped.

## 5. Verified behaviour

Two-phase dev-server run, fresh world, rule on from tick 0:

* phase 1 saved 27 ticks at shutdown (`subTickCount=4464`, `gameTime=524`);
* phase 2 loaded 27, restored `subTickCount 0 -> 4464`, froze before the first tick, and applied all
  27 across 4 chunks;
* each restored tick then executed with **exactly** its saved values, e.g.
  `observer (1,100,0) trigger=386 sub=3798` and `water (2,100,0) trigger=385 sub=3788`;
* cross-chunk global ordering held: lava `so=3702..3704` ran before water `so=3788..`;
* the first newly scheduled tick got `sub=4464`, continuing the restored counter with no collision.

## 6. Known limitations

* **Crash / `kill -9`**: autosave writes chunk data asynchronously and our file is written at
  `saveAllChunks` HEAD; an unclean exit loses everything since the last save. A clean `/stop` is safe
  (the shutdown save is deliberately not short-circuited by `onServerClosed`).
* **Chunks that never reached `BLOCK_TICKING`** hold un-unpacked `pendingTicks` with no absolute
  timing, so they are not snapshotted; whatever entry the store already holds for them is preserved
  instead (which is correct — absolute times do not drift).
* **A tick whose `Block`/`Fluid` id no longer exists** (mod removed) is dropped with a warning;
  `BLOCK`/`FLUID` are `DefaultedRegistry`, so membership is checked explicitly rather than letting
  `getValue()` silently hand back `AIR`/`EMPTY`.
* **Side file out of step with `level.dat`** (typically: the rule was off for a session, so the world
  advanced while the file did not) is detected and warned about by comparing the debug-only recorded
  `gameTime` against the live one. Trigger times are still restored verbatim, so those ticks fire
  immediately. The recorded `gameTime` is never used to re-anchor anything.

## 7. Void world test bed

`tools/setup-void-server.sh [run-dir] [port]` provisions a dev server whose overworld is completely
empty:

```
level-type=minecraft:flat
generator-settings={"layers":[],"biome":"minecraft:the_void","structure_overrides":[],"lakes":false,"features":false}
```

Why bother: a normal world's chunk generation schedules hundreds of ambient water/lava fluid ticks, so
the debug log fills with entries like `flowing_lava (31,-9,-1)` and the signal you care about is
buried. An empty flat world generates nothing, so every captured tick is one the test created.
Startup is also ~3x faster (1.2s vs 3.6s here).

Two gotchas:

* **Void worlds do not keep chunk (0,0) loaded.** Spawn selection differs, so `/setblock 0 100 0 ...`
  fails with `That position is not loaded`. Run `forceload add -1 -1 1 1` first.
* Only the **overworld** generator is replaced (`WorldDimensions.replaceOverworldGenerator`); the
  nether and the end still generate normally.

### Getting a *pending* block event to test with

Block events are normally queued and drained inside the same tick, so they are never observable at
save time. Freeze first — neighbour updates (and therefore `blockEvent`) still run from a `/setblock`,
but `runBlockEvents` is gated by `TickRateManager.runsNormally()`:

```
tick freeze
setblock 10 100 0 minecraft:piston[facing=east]
setblock 10 101 0 minecraft:redstone_block      # queues TRIGGER_EXTEND, cannot be drained
safesave status                                  # blockEventsPending=1
save-all
stop
```

On restart the event is reloaded, the server freezes itself, and `/tick unfreeze` executes it:

```
[safe-save] loaded 1 scheduled tick(s) + 1 block event(s) across 3 dimension(s) ...
[safe-save] minecraft:overworld: restored Level.subTickCount 0 -> 72
[safe-save] minecraft:overworld: restored 1 block event(s) in drain order (0 pre-existing kept behind them)
[safe-save] minecraft:overworld [0, 0]: restored 1 block + 0 fluid tick(s) with absolute timing (kept 0 pre-existing)
[safe-save] froze the server before its first tick (1 scheduled tick(s) + 1 block event(s) restored).
...
[BE][RUN  ] minecraft:overworld minecraft:piston (10,100,0) a=0 b=5 handled=true state=Block{minecraft:piston}[extended=true,facing=east]
[ST][RUN  ] minecraft:overworld/block minecraft:observer (0,100,0) trigger=243 now=243 late=0 prio=NORMAL(0) sub=71
[ST][ADD  ] minecraft:overworld/block minecraft:observer (1,100,0) trigger=245 now=243 delay=2 prio=NORMAL(0) sub=72
```

The piston extends from an event that vanilla would have thrown away, the observer tick fires with its
original `trigger`/`sub`, and the first newly scheduled tick continues from the restored counter (72).

## 8. Moving piston fidelity

Four independent defects in how a mid-flight piston survives a save/load. All four are fixed; the
first is measurable.

### #2 — vanilla saves `progressO`, not `progress`

```java
// PistonMovingBlockEntity
saveAdditional: output.putFloat("progress", this.progressO);   // the PREVIOUS tick's value
loadAdditional: this.progress = input.getFloatOr("progress", 0.0F);
                this.progressO = this.progress;
```

`tick()` sets `progressO = progress` at its head, so the two always differ by 0.5 while a piston is in
flight. Saving the older value **rewinds the piston half a step**, costing exactly one tick per
save/load cycle (once — it does not accumulate, since a frozen piston is not ticked and re-saves the
same value).

Worse, `moveStuckEntities` (honey block, horizontal only) applies `deltaProgress` unconditionally with
**no overlap test**, so repeating a half step drags a passenger an extra 0.5:

```java
for (Entity entity : level.getEntities(null, aabb, e -> matchesStickyCritera(aabb, e, pos)))
    moveEntityByPiston(movement, entity, deltaProgress, movement);   // always 0.5
```

`moveCollidedEntities` *does* intersection-test, so the ordinary push side is largely unaffected — the
asymmetry is what makes this easy to miss.

**Measured**, sticky piston + honey block + armour stand passenger, saved mid-flight and reloaded:

| `safeSave` | honey block moved | passenger dragged | passenger final x |
|---|---|---|---|
| `false` (vanilla) | 1.0 | **1.5** ❌ | 3.0 |
| `true` (fixed) | 1.0 | **1.0** ✅ | 2.5 |

The bug is visible in the NBT itself — live `progress` is 0.5 while the serialized form says `0.0f`:

```
{ ..., id: "minecraft:piston", extending: 1b, progress: 0.0f,
  safesave_progress: 0.5f, safesave_progress_o: 0.0f,
  safesave_last_ticked: 202L, safesave_order: 0L }
```

Vanilla's `progress` key is left **exactly as vanilla writes it**, so removing this mod degrades to
vanilla behaviour rather than corrupting anything. A missing `safesave_progress` is the sentinel for
"written before this mod / with the rule off", in which case vanilla's already-applied values are left
alone.

### #5 — `lastTicked` is not persisted

`PistonBaseBlock.checkIfExtend` uses `getGameTime() == pistonEntity.getLastTicked()` as one of three
disjuncts deciding `TRIGGER_DROP` (2) vs `TRIGGER_CONTRACT` (1). The other two usually mask its loss,
but not when `isHandlingTick()` is false — i.e. **player-triggered** updates, which are processed after
`level.tick()` has already cleared the flag. Now stored as an absolute game time.

### #4 — block entity tick order

`Level.blockEntityTickers` is a `List` ticked in insertion order. That order changes across a reload:

| stage | order |
|---|---|
| before save | `moveBlocks` creates PMEs in reverse `toPush`, arm last → **creation order** |
| written | `ChunkAccess.getBlockEntitiesPos()` = `Sets.newHashSet(...)` → **hash order** |
| loaded | `registerAllBlockEntitiesAfterLevelLoad` iterates `pendingBlockEntities` (`HashMap`) → **hash order** |

It is deterministic, just unrelated to creation order — and it matters when two adjacent pistons
finalise on the same tick, because each runs `updateFromNeighbourShapes` and so observes the other's
result. A persisted creation sequence number is used to rewrite **only the ticker slots occupied by
moving pistons**, in ascending order; every other ticker keeps its exact index.

The rebuild filters candidates on `getBlockState(pos).is(Blocks.MOVING_PISTON)` rather than
`getBlockEntity`, because `Level.getBlockEntity` uses `EntityCreationType.IMMEDIATE` and would promote
pending block entities level-wide, creating them earlier than vanilla does. (It also sidesteps the
`BlockEntityType` → `BlockEntityTypes` rename between 26.1 and 26.2.)

### #3 — a cross-chunk push is not atomic on load

A push spans up to 12 blocks plus sticky branches, so it routinely crosses chunk boundaries — yet PME
ticking is gated **per chunk** (`LevelChunk.isTicking`). On load chunks come up staggered, so the halves
of one push finalise on different ticks; since finalisation runs `updateFromNeighbourShapes` and
`neighborChanged`, the early half resolves against a world where the rest of the structure is still
`MOVING_PISTON`, and a slime/honey structure can tear in two.

The chunks holding a moving piston are recorded in the side file (`piston_chunks`, v3) — they must be
known *before* any of them is loaded, which is impossible by scanning. On load the gate waits until
every one of them satisfies `isPositionTickingWithEntitiesLoaded` and then reports:

```
[safe-save] minecraft:overworld: all 1 chunk(s) holding a moving piston are now block-ticking -
            the whole push will resume on one tick. Safe to '/tick unfreeze'.
```

This deliberately **does not add chunk tickets**: force-loading chunks the player never asked for would
change world behaviour, and a chunk outside the simulation distance never becomes tickable in vanilla
either — so waiting forever would be wrong. Hence a 600-server-tick timeout with a warning naming the
chunks. Note the timeout counts *server* ticks, since `gameTime` does not advance while frozen.

> Freezing until the chunks are ready also removes vanilla's scheduled-tick drift for free: the
> re-anchor is `triggerTick = T_unpack + delay`, and `gameTime` does not advance while frozen, so every
> chunk loaded during the freeze gets `T_unpack == T_save`. It does **not** fix the per-chunk
> `subTickOrder` renumbering or the `markUnsaved` loss — those still need the side file.

### Still unfixed

* **#6 `MOVING_PISTON` without its block entity is a permanent ghost.** `MovingPistonBlock.newBlockEntity`
  returns `null`, so the block cannot rebuild its own block entity; it renders `INVISIBLE` with an empty
  collision shape and can only be cleared by right-clicking it. Both parts live in the same chunk NBT so
  they are normally consistent — this only triggers on corruption or a registry change.
