FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends openssh-client && rm -rf /var/lib/apt/lists/*
COPY keymaster-avatar /usr/local/bin/keymaster-avatar
COPY ssh-service-avatar /usr/local/bin/ssh-service-avatar
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/keymaster-avatar /usr/local/bin/ssh-service-avatar /usr/local/bin/entrypoint.sh
ENTRYPOINT ["entrypoint.sh"]
