FROM debian:trixie-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    openssh-client gnupg procps && rm -rf /var/lib/apt/lists/*
COPY keymaster-avatar /usr/local/bin/keymaster-avatar
COPY km-ssh-sa /usr/local/bin/km-ssh-sa
COPY km-gpg-sa /usr/local/bin/km-gpg-sa
COPY scd-shim /usr/local/bin/scd-shim
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/keymaster-avatar /usr/local/bin/km-ssh-sa \
    /usr/local/bin/km-gpg-sa /usr/local/bin/scd-shim \
    /usr/local/bin/entrypoint.sh
ENTRYPOINT ["entrypoint.sh"]
