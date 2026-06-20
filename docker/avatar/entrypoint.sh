#!/bin/bash
set -e

# Set up GNUPGHOME for GPG operations
export GNUPGHOME=/tmp/gnupg-home
mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"

# Start keymaster-avatar in background, forwarding all arguments
keymaster-avatar "$@" &
AVATAR_PID=$!

# Wait for local API socket to appear (up to 10s)
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

# Start km-ssh-sa in background
km-ssh-sa --log-level debug &
SSH_PID=$!

echo "Avatar PID=$AVATAR_PID, SSH service PID=$SSH_PID"

# Wait for either process to exit
wait -n $AVATAR_PID $SSH_PID
EXIT_CODE=$?

# Kill the other process
kill $AVATAR_PID $SSH_PID 2>/dev/null
exit $EXIT_CODE
