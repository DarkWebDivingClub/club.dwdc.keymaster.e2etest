#!/bin/bash
set -e
export GNUPGHOME=/tmp/gnupg-home
mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"

# Start keymaster-avatar in background
keymaster-avatar "$@" &
AVATAR_PID=$!

# Wait for local API socket to appear
TIMEOUT=20
ELAPSED=0
while [ ! -S /tmp/keymaster-avatar.sock ] && [ "$ELAPSED" -lt "$TIMEOUT" ]; do
    sleep 0.5
    ELAPSED=$((ELAPSED + 1))
done

if [ ! -S /tmp/keymaster-avatar.sock ]; then
    echo "ERROR: local API socket not created within ${TIMEOUT}s" >&2
    kill $AVATAR_PID 2>/dev/null
    exit 1
fi

# Start service avatars with auto-restart (like systemd Restart=on-failure)
(while true; do km-ssh-sa --log-level debug; sleep 1; done) &
(while true; do km-gpg-sa --log-level debug; sleep 1; done) &
(while true; do km-nostr-sa --log-level debug; sleep 1; done) &

echo "Avatar PID=$AVATAR_PID, SAs started"

# Wait for avatar to exit
wait $AVATAR_PID
