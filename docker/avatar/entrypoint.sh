#!/bin/bash
set -e
export GNUPGHOME=/tmp/gnupg-home
mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"
exec keymaster-avatar "$@"
