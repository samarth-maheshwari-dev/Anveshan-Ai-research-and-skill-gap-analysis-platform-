#!/usr/bin/env bash
# Local backup (TECHSPEC §16.7 without object storage): pg_dump → /backups, verify, rotate.
set -euo pipefail

ts=$(date -u +%Y-%m-%dT%H%MZ)
file="/backups/anveshan-${ts}.dump"

pg_dump -Fc -Z 6 -f "${file}.part"
pg_restore --list "${file}.part" > /dev/null      # fails if the dump is unreadable
mv "${file}.part" "$file"

find /backups -maxdepth 1 -name 'anveshan-*.dump' -mtime +"${KEEP_DAYS:-14}" -delete

echo "backup ok: $(basename "$file") ($(du -h "$file" | cut -f1))"
