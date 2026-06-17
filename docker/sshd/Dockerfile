FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends openssh-server && rm -rf /var/lib/apt/lists/*
RUN mkdir /run/sshd
RUN useradd -m -s /bin/bash testuser
RUN mkdir -p /home/testuser/.ssh && chmod 700 /home/testuser/.ssh
COPY authorized_keys /home/testuser/.ssh/authorized_keys
RUN chown -R testuser:testuser /home/testuser/.ssh && chmod 600 /home/testuser/.ssh/authorized_keys
EXPOSE 22
CMD ["/usr/sbin/sshd", "-D", "-e"]
