#!/bin/bash
# Ad-blocking DNS via Android's Private DNS (Android 9+). Does NOT block YouTube ads.
#   tools/adblock_dns.sh on    -> dns.adguard-dns.com
#   tools/adblock_dns.sh off   -> back to automatic
cd "$(dirname "$0")/.."
case "$1" in
  on)  tools/tv shell 'settings put global private_dns_specifier dns.adguard-dns.com'
       tools/tv shell 'settings put global private_dns_mode hostname'
       sleep 3; tools/tv shell 'ping -c1 -W3 doubleclick.net | head -1' ;;
  off) tools/tv shell 'settings put global private_dns_mode opportunistic' ;;
  *)   echo "usage: $0 on|off"; exit 1 ;;
esac
tools/tv shell 'settings get global private_dns_mode'
