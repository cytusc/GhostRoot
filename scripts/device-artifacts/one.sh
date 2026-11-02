#!/system/bin/sh
# one.sh v6 - executed by seedkeeper (atcid_vendor_socket) as root, in kernel-permissive window
#
# v6 (2026-11-02): W1c 摘除连续两个 boot 失败（insmod: No such device,
# vr_disarmed=0 —— vivo vr.ko sys_exit 探针全程武装）。诊断增强：
# 记录 dfroot_vr.ko 的 size/md5 与 uname -r，BTF 失配排查用。
# NOTE: /system/bin/su is magic-mounted by KernelSU and is NOT visible inside
# the seedkeeper daemon's pre-existing mount namespace -- it only shows up for
# processes spawned after the mount (e.g. adb shell). So su-path polling here
# is useless; kernel-level evidence (/proc/modules) IS namespace-global.
{
echo "=== one-chain $(date +%T) ==="
id
echo "enforce=$(cat /sys/fs/selinux/enforce 2>/dev/null)"
echo "--- W1c: disarm vr.ko sys_exit probe (dfroot, BTF auto)"
if [ -f /data/local/tmp/dfroot_vr.ko ]; then
  echo "ko: size=$(wc -c < /data/local/tmp/dfroot_vr.ko) md5=$(md5sum /data/local/tmp/dfroot_vr.ko 2>/dev/null | awk '{print $1}') uname=$(uname -r)"
  insmod /data/local/tmp/dfroot_vr.ko 2>&1 | head -5
  grep -q dfroot /proc/modules 2>/dev/null && echo "dfroot still loaded" || echo "dfroot unloaded (init -ENODEV = normal)"
  rm -f /dev/dfm0 /dev/dfvra /dev/dfvrx /dev/dfvre 2>/dev/null
else
  echo "dfroot_vr.ko missing, skip W1c"
fi

cd /data/local/tmp
chmod 0755 b1 2>/dev/null
./b1 late-load --kmi android13-5.15 --allow-shell
echo "late-load rc=$?"
echo "--- poll kernelsu module up to 30s (namespace-global):"
k=0
while [ $k -lt 30 ]; do
  if grep -q kernelsu /proc/modules 2>/dev/null; then break; fi
  k=$((k+1)); sleep 1
done
echo "ks_module_present=$(grep -c kernelsu /proc/modules 2>/dev/null) after ${k}s"
echo "--- modules:"
grep kernelsu /proc/modules 2>/dev/null
echo "--- ksu dir:"
ls -la /data/adb/ksu/bin 2>/dev/null | head -5
echo "--- note: su visibility is verified from the SH side (autoroot root_ok),"
echo "--- because the seedkeeper mount namespace predates the magic mount."
echo "=== done $(date +%T) ==="
} > /data/local/tmp/.one.log 2>&1
chmod 0666 /data/local/tmp/.one.log
