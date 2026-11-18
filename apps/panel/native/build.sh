#!/bin/sh
# builds lib/<abi>/libajtty.so (static executables, named .so so Android unpacks them like snapclient)
set -e
cd "$(dirname "$0")"
F="-Os -static -nostdlib -ffreestanding -fno-stack-protector -fno-builtin -fuse-ld=lld -Wl,-e,_start -Wl,--gc-sections -fno-pie -Wl,--no-pie -s"
clang --target=armv7a-linux-androideabi -marm -fomit-frame-pointer $F -o ../lib/armeabi-v7a/libajtty.so ajtty.c
clang --target=aarch64-linux-android $F -o ../lib/arm64-v8a/libajtty.so ajtty.c
ls -l ../lib/*/libajtty.so
