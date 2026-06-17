#!/bin/bash
# demo-start.sh — start the Docker-based demo environment.
# Usage: bash /projectDS/demo-start.sh [--build]
#   --build  rebuild images from the current JAR before starting (run this after scp-ing a new JAR)

cd /projectDS

# --build: rebuild the image from the current JAR AND recreate the running
# containers so they actually pick up the new code. Without --force-recreate,
# `up` leaves already-running containers on the OLD image (the stale-container
# trap). --build folds the image rebuild into the same step.
UP_FLAGS=""
if [ "${1}" = "--build" ]; then
  echo "[0/4] Rebuilding image from current JAR + recreating containers..."
  UP_FLAGS="--build --force-recreate"
fi

echo "[1/4] Starting core services..."
sudo docker compose up -d $UP_FLAGS naming-server node-a node-b node-c nginx

echo "[2/4] Ensuring dynamic node containers exist..."
sudo docker compose create node-d node-e node-f 2>/dev/null; true

echo "[3/4] Waiting for nodes to register..."
sleep 5

echo "[4/4] Reloading nginx..."
sudo docker exec projectds-nginx-1 nginx -s reload

echo ""
echo "Ready — http://143.129.43.114"
