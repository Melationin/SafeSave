#!/usr/bin/env bash
# Set up a void-world dev server for SafeSave.
#
#   tools/setup-void-server.sh [run-dir] [port]
#
# A void world is the ideal test bed for tick/redstone work: a normal world's chunk generation
# schedules hundreds of ambient water/lava fluid ticks (you will see entries like
# `flowing_lava (31,-9,-1)` in the debug log), which drown out whatever the test itself creates.
# An empty flat world generates nothing at all, so every captured tick is yours.
#
# After running this:
#   JAVA_HOME=<jdk25> ./gradlew :26.2:runServer --offline
#   > forceload add -1 -1 1 1          # void worlds do NOT keep chunk (0,0) loaded by default
#   > safesave scheduledTicks true
#   > setblock 0 100 0 minecraft:observer[facing=east]
set -euo pipefail

RUN_DIR="${1:-run}"
PORT="${2:-25565}"
LEVEL_NAME="void"

mkdir -p "$RUN_DIR/$LEVEL_NAME"

echo "eula=true" > "$RUN_DIR/eula.txt"

cat > "$RUN_DIR/server.properties" <<EOF
online-mode=false
level-name=$LEVEL_NAME
level-type=minecraft:flat
generator-settings={"layers":[],"biome":"minecraft:the_void","structure_overrides":[],"lakes":false,"features":false}
view-distance=6
simulation-distance=6
max-players=4
spawn-protection=0
sync-chunk-writes=true
server-port=$PORT
motd=SafeSave void world
EOF

# Carpet reads rules from <world>/carpet.conf at MinecraftServer.loadLevel HEAD. Setting the rule with
# /carpet is session-only unless made permanent, so seed the file directly.
if ! grep -q '^safeSave ' "$RUN_DIR/$LEVEL_NAME/carpet.conf" 2>/dev/null; then
  echo "safeSave true" >> "$RUN_DIR/$LEVEL_NAME/carpet.conf"
fi

echo "void server ready in '$RUN_DIR' (level '$LEVEL_NAME', port $PORT)"
echo
echo "generator-settings: empty layers + minecraft:the_void biome"
echo "  -> only the OVERWORLD generator is replaced (WorldDimensions.replaceOverworldGenerator);"
echo "     the nether and the end still generate normally."
echo
cat "$RUN_DIR/$LEVEL_NAME/carpet.conf"
