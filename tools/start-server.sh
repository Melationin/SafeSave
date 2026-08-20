#!/usr/bin/env bash
# Launch the SafeSave dev server detached, with a FIFO for console input.
#   start-server.sh            # 26.2 void world
# Console:  echo "<command>" > /tmp/ce-console
# Stop:     echo "stop"      > /tmp/ce-console
# Log:      tail -f /tmp/ce-server.log
#
# NOTE: do NOT run `./gradlew build` while this is up - Gradle cannot run two builds against the
# same project concurrently and will terminate the runServer build (cleanly, but it does stop).
set -u
PROJ=/home/zhddsj/java/carpet-example
FIFO=/tmp/ce-console
LOG=/tmp/ce-server.log
PIDFILE=/tmp/ce-server.pid
export JAVA_HOME=$HOME/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2

rm -f "$FIFO" "$LOG" "$PIDFILE"
mkfifo "$FIFO"

cd "$PROJ"

# Hold the FIFO open forever so the server's stdin never hits EOF when a writer closes.
setsid nohup bash -c 'exec 9>'"$FIFO"'; while :; do sleep 3600; done' >/dev/null 2>&1 &
echo "holder=$!" >> "$PIDFILE"

# Detach the server itself so it outlives the launching shell/session.
setsid nohup bash -c "exec ./gradlew :26.2:runServer --offline --console=plain < '$FIFO' > '$LOG' 2>&1" >/dev/null 2>&1 &
echo "gradle=$!" >> "$PIDFILE"

echo "launched; log=$LOG fifo=$FIFO"
