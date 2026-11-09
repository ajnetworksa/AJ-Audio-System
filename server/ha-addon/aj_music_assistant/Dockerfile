ARG BUILD_FROM
FROM ${BUILD_FROM}
COPY aj /tmp/aj
RUN sh /tmp/aj/install_ajsnap.sh /tmp/aj && rm -rf /tmp/aj
