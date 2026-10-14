#!/usr/bin/env bash
# Syncs a directory tree to simpan with rclone and back, and checks every byte.
# Usage: SIMPAN_ENDPOINT=http://127.0.0.1:9000 SIMPAN_ACCESS_KEY=... SIMPAN_SECRET_KEY=... scripts/rclone-e2e.sh
set -euo pipefail
RCLONE=${RCLONE:-rclone}
export RCLONE_CONFIG_SIMPAN_TYPE=s3
export RCLONE_CONFIG_SIMPAN_PROVIDER=Other
export RCLONE_CONFIG_SIMPAN_ENDPOINT=${SIMPAN_ENDPOINT:-http://127.0.0.1:9000}
export RCLONE_CONFIG_SIMPAN_ACCESS_KEY_ID=$SIMPAN_ACCESS_KEY
export RCLONE_CONFIG_SIMPAN_SECRET_ACCESS_KEY=$SIMPAN_SECRET_KEY
export RCLONE_CONFIG_SIMPAN_FORCE_PATH_STYLE=true

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/src/photos/2026" "$work/src/docs/deep/er" "$work/back"
for i in $(seq 1 30); do head -c $((RANDOM * 7)) /dev/urandom > "$work/src/photos/2026/img-$i.jpg"; done
head -c $((24 * 1024 * 1024)) /dev/urandom > "$work/src/docs/big video.mp4"   # multipart, space in name
echo "plus+sign" > "$work/src/docs/deep/er/a+b=c&d.txt"
echo "unicode" > "$work/src/docs/deep/er/résumé-日本-😀.txt"

bucket="rclone-$(date +%s)"
$RCLONE mkdir "simpan:$bucket"
$RCLONE sync "$work/src" "simpan:$bucket" --transfers 8 --s3-upload-cutoff 8M --s3-chunk-size 5M
$RCLONE check "$work/src" "simpan:$bucket" --download
$RCLONE sync "simpan:$bucket" "$work/back" --transfers 8
diff -r "$work/src" "$work/back"
echo "changed" > "$work/src/docs/deep/er/a+b=c&d.txt"
rm "$work/src/photos/2026/img-1.jpg"
$RCLONE sync "$work/src" "simpan:$bucket"
$RCLONE check "$work/src" "simpan:$bucket" --download
$RCLONE purge "simpan:$bucket"
echo "rclone: sync, check (every byte), incremental sync and purge all passed"
