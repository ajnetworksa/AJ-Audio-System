#!/system/bin/sh
# zigbee_probe.sh - READ ONLY, runs on a panel (pushed by zigbee_probe.bat). Shows how the Zigbee radio is wired.
sec() { echo; echo "--- $1 ---"; }
sec device
getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release
getprop ro.board.platform; getprop ro.build.type; echo "debuggable=$(getprop ro.debuggable)"; echo "selinux=$(getenforce 2>&1)"
sec "zigbee / serial properties"
getprop | grep -iE 'zig|tuya|uart|serial|tty'
sec "serial ports (owner, rights)"
ls -lZ /dev/ttyS* /dev/ttyUSB* /dev/ttyACM* 2>&1
sec "which process has a serial port open"
for p in /proc/[0-9]*; do
  l=$(ls -l $p/fd 2>/dev/null | grep -E 'ttyS|ttyUSB|ttyACM')
  [ -n "$l" ] && { echo "pid ${p##*/}: $(tr '\000' ' ' < $p/cmdline) [uid $(stat -c %u $p 2>/dev/null)]"; echo "$l"; }
done
sec "serial ports in sysfs"
for t in /sys/class/tty/ttyS* /sys/class/tty/ttyUSB* /sys/class/tty/ttyACM*; do [ -e "$t" ] && echo "$t -> $(readlink $t/device 2>/dev/null)"; done
sec "usb devices"
for d in /sys/bus/usb/devices/*; do [ -f $d/idVendor ] && echo "$d $(cat $d/idVendor):$(cat $d/idProduct) $(cat $d/manufacturer 2>/dev/null) $(cat $d/product 2>/dev/null)"; done
sec "tuya / gateway apps"
pm list packages -f 2>/dev/null | grep -iE 'tuya|zig|gateway|smart|iot|judian|yjy|home'
sec "tuya / gateway processes"
ps -A -o USER,PID,NAME 2>/dev/null | grep -iE 'tuya|zig|gateway|iot|judian|yjy|sshd|mqtt'
sec "listening ports"
netstat -tlnu 2>/dev/null | grep -E 'LISTEN|udp' | head -60
sec "zigbee in the android log"
logcat -d -v time 2>/dev/null | grep -iE 'zigbee|ezsp|znp|ttyS|tuya.*(gw|gateway|zig)|ncp|efr32|bootloader' | tail -80
sec "tuya app data (names only)"
ls -la /sdcard 2>/dev/null | grep -iE 'tuya|zig|gw' 
echo; echo "=== end ==="
