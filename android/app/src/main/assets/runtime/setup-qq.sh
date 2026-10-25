#!/bin/sh
set -eu
export DEBIAN_FRONTEND=noninteractive
export XDG_RUNTIME_DIR=/tmp/qqagent-xdg
mkdir -p /var/lib/apt/lists/partial /var/cache/apt/archives/partial /tmp/qqagent-xdg
chmod 700 /tmp/qqagent-xdg
# Services must not be started by maintainer scripts in the app-managed guest.
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod 755 /usr/sbin/policy-rc.d
apt_get() {
  apt-get -o APT::Sandbox::User=root -o Acquire::Retries=2 \
    -o APT::Keep-Downloaded-Packages=true -o Acquire::Languages=none \
    -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 \
    -o Acquire::https::Verify-Peer=true -o Acquire::https::Verify-Host=true "$@"
}
if dpkg --audit | grep -q .; then
  echo QQAGENT_RECOVER_INTERRUPTED_PACKAGES
  dpkg --configure -a || apt_get -f install -y
fi
if [ ! -f /qqagent-state/qq-dependencies.ready ]; then
  echo QQAGENT_APT_UPDATE
  apt_get update
  echo QQAGENT_APT_INSTALL
  apt_get install -y --no-install-recommends xvfb xauth libgtk-3-0t64 libnotify4 libnss3 \
    libxss1 libxtst6 xdg-utils libatspi2.0-0t64 libuuid1 libsecret-1-0 libgbm1 libasound2t64
  dpkg-query -W -f='${Package}\t${Version}\t${Architecture}\n' > /qqagent-state/debian-packages.tsv
  if find /var/cache/apt/archives -maxdepth 1 -name '*.deb' | grep -q .; then
    sha256sum /var/cache/apt/archives/*.deb > /qqagent-state/debian-package-archives.sha256
  else
    printf 'APT package cache was cleared; exact installed versions are in debian-packages.tsv\n' > /qqagent-state/debian-package-cache.txt
  fi
  printf 'signed Debian repositories; installed versions recorded\n' > /qqagent-state/qq-dependencies.ready
fi
if [ ! -f /opt/QQ/qq ]; then
  echo QQAGENT_QQ_EXTRACT
  dpkg-deb -x /qqagent-downloads/QQ_3.2.32_260812_arm64_01.deb /
fi
# Follow the upstream Linux Shell entry-point configuration. NapCat stays unmodified.
/opt/node/bin/node /qqagent-scripts/configure-napcat.cjs
echo QQAGENT_QQ_LDD
ldd /opt/QQ/qq > /qqagent-state/qq-ldd.txt
cat /qqagent-state/qq-ldd.txt
if grep -q 'not found' /qqagent-state/qq-ldd.txt; then
  echo 'QQAGENT_QQ_MISSING_LIBRARIES' >&2
  exit 1
fi
echo QQAGENT_QQ_COMPONENTS_OK
