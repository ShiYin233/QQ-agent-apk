#!/bin/sh
set -eu
export DEBIAN_FRONTEND=noninteractive
apt_get() {
  apt-get -o APT::Sandbox::User=root -o Acquire::Retries=2 -o APT::Keep-Downloaded-Packages=true \
    -o Acquire::Languages=none -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 \
    -o Acquire::https::Verify-Peer=true -o Acquire::https::Verify-Host=true "$@"
}
if dpkg --audit | grep -q .; then dpkg --configure -a || apt_get -f install -y; fi
apt_get install -y --no-install-recommends ffmpeg
dpkg-query -W -f='${Package}\t${Version}\t${Architecture}\n' > /qqagent-state/debian-packages-with-media.tsv
sha256sum /var/cache/apt/archives/*.deb > /qqagent-state/debian-media-archives.sha256
/opt/node/bin/node /qqagent-scripts/probe-media.mjs
