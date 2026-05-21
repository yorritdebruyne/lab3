#!/bin/bash
# demo-start.sh — start the Docker-based demo environment.
# Usage: bash /projectDS/demo-start.sh

cd /projectDS

echo "[1/4] Starting core services..."
sudo docker compose up -d naming-server node-a node-b node-c nginx

echo "[2/4] Ensuring dynamic node containers exist..."
sudo docker compose create node-d node-e node-f 2>/dev/null; true

echo "[3/4] Waiting for nodes to register..."
sleep 5

echo "[4/4] Reloading nginx..."
sudo docker exec projectds-nginx-1 nginx -s reload

echo ""
echo "Ready — http://143.129.43.114"
