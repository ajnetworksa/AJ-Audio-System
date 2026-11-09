# AJ Music Assistant = official image + ajsnap provider + patches (D-008)
# build (from the server/ folder):  docker build -f docker/Dockerfile -t aj-music-assistant:2.10.9 .
ARG MA_VERSION=2.10.9
FROM ghcr.io/music-assistant/server:${MA_VERSION}
COPY ajsnap-provider/ajsnap /tmp/aj/ajsnap
COPY patches /tmp/aj/patches
COPY install_ajsnap.sh /tmp/aj/install_ajsnap.sh
RUN sh /tmp/aj/install_ajsnap.sh /tmp/aj && rm -rf /tmp/aj
LABEL org.opencontainers.image.title="AJ Music Assistant" org.opencontainers.image.vendor="AJ Network Solutions"
