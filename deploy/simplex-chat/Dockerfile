# Client simplex-chat en ligne de commande, non modifié (AGPL-3.0),
# exécuté comme processus séparé et piloté par son API WebSocket.
FROM ubuntu:24.04

ARG SIMPLEX_CHAT_VERSION=v7.1.2

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl libgmp10 libssl3 zlib1g \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL -o /usr/local/bin/simplex-chat \
      "https://github.com/simplex-chat/simplex-chat/releases/download/${SIMPLEX_CHAT_VERSION}/simplex-chat-ubuntu-24_04-x86_64" \
 && chmod +x /usr/local/bin/simplex-chat

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

VOLUME /data
EXPOSE 5225
ENTRYPOINT ["/entrypoint.sh"]
