FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    openssh-client gnupg && rm -rf /var/lib/apt/lists/*
COPY keymaster-avatar /usr/local/bin/keymaster-avatar
COPY km-ssh-sa /usr/local/bin/km-ssh-sa
COPY iz-scdaemon /usr/local/bin/iz-scdaemon
COPY libiz_pkcs11_gpg.so /usr/local/lib/libiz_pkcs11_gpg.so
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/keymaster-avatar /usr/local/bin/km-ssh-sa \
    /usr/local/bin/iz-scdaemon /usr/local/bin/entrypoint.sh
ENTRYPOINT ["entrypoint.sh"]
