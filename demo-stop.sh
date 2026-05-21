#!/bin/bash
# demo-stop.sh — stop all containers without removing them.
# Dynamic node containers (node-d/e/f) are preserved so 'create' is not needed next session.
# Usage: bash /projectDS/demo-stop.sh

cd /projectDS
sudo docker compose stop
echo "All containers stopped. Run demo-start.sh to restart."
