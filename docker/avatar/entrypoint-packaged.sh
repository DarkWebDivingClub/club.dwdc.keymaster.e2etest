#!/bin/bash
set -e
export GNUPGHOME=/tmp/gnupg-home
mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"
# Avatar is in /usr/bin, SAs are in /usr/lib/keymaster-avatar/ (off PATH).
# Avatar must find SAs via config (service_avatar_dir) or sibling lookup.
exec keymaster-avatar "$@"
