#!/system/bin/sh
# fix.sh v2 (v28) — 把管理器 libksud.so 换成修复版（magica skip）
# 判据纪律：唯一结论 = 最终 md5 是否等于 FIXED_MD5。
# 每步打印自己的 rc，任何一步都不得单独宣称"已替换/完成"。
# 退出码：0=VERIFIED（含 already-patched）3=环境缺失 4=cp 失败 5=替换后 md5 不符
PKG=me.weishu.kernelsu
SRC=/data/local/tmp/libksud.so
FIXED_MD5="2c84145e3efb6f7f045fef3ed1eea4c6"

APK=$(pm path "$PKG" 2>/dev/null | sed 's/package://' | head -1)
[ -z "$APK" ] && { echo "[FAIL] manager not installed"; exit 3; }
LIBDIR="${APK%/base.apk}/lib/arm64"
LIB="$LIBDIR/libksud.so"
[ -f "$LIB" ] || { echo "[FAIL] lib not found: $LIB"; exit 3; }
[ -f "$SRC" ] || { echo "[FAIL] missing source: $SRC"; exit 3; }

CUR=$(md5sum "$LIB" 2>/dev/null | awk '{print $1}')
echo "[CHECK] manager lib current md5=$CUR"

if [ "$CUR" = "$FIXED_MD5" ]; then
    echo "[SKIP] already fixed (md5 match) — no copy performed"
    echo "[VERIFIED] manager-patched (pre-existing)"
    exit 0
fi

OWNER=$(stat -c '%u:%g' "$LIB" 2>/dev/null)
cp "$SRC" "$LIB" 2>&1; RC_CP=$?
echo "[STEP] cp rc=$RC_CP"
if [ $RC_CP -ne 0 ]; then
    echo "[FAIL] cp failed — lib NOT replaced (may be transient right after KSU load; retry in ~10s)"
    exit 4
fi
chown "$OWNER" "$LIB" 2>/dev/null; echo "[STEP] chown rc=$?"
chmod 755 "$LIB" 2>/dev/null;      echo "[STEP] chmod rc=$?"
restorecon "$LIB" 2>/dev/null || chcon u:object_r:apk_data_file:s0 "$LIB" 2>/dev/null
echo "[STEP] relabel rc=$?"

NEW=$(md5sum "$LIB" 2>/dev/null | awk '{print $1}')
if [ "$NEW" = "$FIXED_MD5" ]; then
    echo "[VERIFIED] manager-patched md5=$NEW"
    exit 0
fi
echo "[FAIL] md5 after replace=$NEW expected=$FIXED_MD5"
exit 5
