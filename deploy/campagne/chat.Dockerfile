# Client simplex-chat non modifié (AGPL-3.0), binaire préparé par prepare.sh.
FROM ubuntu:24.04
COPY simplex-chat /usr/local/bin/simplex-chat
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /usr/local/bin/simplex-chat /entrypoint.sh
VOLUME /data
EXPOSE 5225
ENTRYPOINT ["/entrypoint.sh"]
