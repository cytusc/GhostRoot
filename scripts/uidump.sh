#!/bin/bash
# uidump.sh — 用无障碍节点树读取前台界面文本（比截图识别快且准）
#
# 用法:
#   bash uidump.sh              # dump 当前前台窗口并打印文本节点
#   bash uidump.sh --scroll N   # dump 后向上滑动 N 次，每次滑动后各打印一屏
#   bash uidump.sh --raw        # 打印原始 XML（体积大，调试用）
#   bash uidump.sh --pkg com.x  # 只看指定包的窗口（先启动该包）
#
# 依赖: adb in PATH；uiautomator dump 可用（若 AMS 残留注册需先重置
#       AccessibilityManagerService，否则报 "UiAutomationService already registered"）
set -u

ADB="${ADB:-$(command -v adb)}"
[ -x "$ADB" ] || ADB="/Users/hoyo/WorkBuddy AI/2026-10-28-12-02-20/tools/platform-tools/adb"
REMOTE=/data/local/tmp/_uidump.xml
LOCAL=/tmp/_uidump.xml

MODE=dump
COUNT=0
for a in "$@"; do
  case "$a" in
    --scroll) MODE=scroll ;;
    --raw) MODE=raw ;;
    [0-9]*) COUNT="$a" ;;
  esac
done

dump_once() {
  "$ADB" shell uiautomator dump "$REMOTE" >/dev/null 2>&1
  "$ADB" pull "$REMOTE" "$LOCAL" >/dev/null 2>&1
  if [ ! -s "$LOCAL" ]; then
    echo "[uidump] dump 失败（无文件；若报 already registered，需重置 AMS）" >&2
    return 1
  fi
  if [ "$MODE" = "raw" ]; then
    cat "$LOCAL"
    return 0
  fi
  python3 - "$LOCAL" <<'PY'
import re, sys
x = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'<node[^>]*>', x):
    s = m.group(0)
    def g(k):
        mm = re.search(k + r'="([^"]*)"', s)
        return mm.group(1) if mm else ''
    txt, desc, cls, rid, bounds = g('text'), g('content-desc'), g('class'), g('resource-id'), g('bounds')
    label = txt or desc
    if not label:
        continue
    cls_short = cls.split('.')[-1]
    print(f"{label}\t[{cls_short}{'/' + rid.split('/')[-1] if rid else ''}]\t{bounds}")
PY
}

if [ "$MODE" = "scroll" ]; then
  n=${COUNT:-3}
  for i in $(seq 1 "$n"); do
    echo "=========== SCREEN $i ==========="
    dump_once || exit 1
    "$ADB" shell input swipe 540 1900 540 700 250 >/dev/null 2>&1
    sleep 1.2
  done
else
  dump_once
fi
