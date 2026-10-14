#!/system/bin/sh
# XiaoFeng 崩溃日志采集脚本
# 作用：采集微信版本、模块状态、Zygisk 环境，以及微信启动崩溃的 logcat 日志
# 用法（需要 root）：
#   su -c 'sh /sdcard/Download/xfeng-crashlog.sh'
#   或先进入 root shell：su   然后：sh /sdcard/Download/xfeng-crashlog.sh

OUT=/sdcard/xfeng-crashlog
SUMMARY=$OUT/summary.txt
LOGFILE=$OUT/logcat.log

mkdir -p "$OUT" 2>/dev/null || { echo "无法创建 $OUT"; exit 1; }

if [ "$(id -u 2>/dev/null)" != "0" ]; then
  echo "当前不是 root，请用 root 运行：su -c 'sh $0'"
  exit 1
fi

PKG=com.tencent.mm
ACT=$PKG/com.tencent.mm.ui.LauncherUI

echo "[1/4] 采集环境与模块信息..."
{
  echo "==== XiaoFeng 崩溃采集 ===="
  echo "时间: $(date)"
  echo "机型: $(getprop ro.product.brand) $(getprop ro.product.model)"
  echo "架构: $(getprop ro.product.cpu.abi)"
  echo "系统: Android $(getprop ro.build.version.release) (SDK $(getprop ro.build.version.sdk))"
  echo
  echo "== 微信版本 =="
  dumpsys package $PKG 2>/dev/null | grep -E 'versionName|versionCode|targetSdk' | head -6
  echo
  echo "== 已安装模块 (/data/adb/modules) =="
  ls -1 /data/adb/modules 2>/dev/null
  echo
  echo "== xfeng_zygisk 模块目录 =="
  ls -l /data/adb/modules/xfeng_zygisk 2>/dev/null
  echo
  echo "== zygisk 目录 =="
  ls -l /data/adb/modules/xfeng_zygisk/zygisk 2>/dev/null
  echo
  echo "== 注入目标 (injection-targets.tsv) =="
  cat /data/adb/xfeng_zygisk/injection-targets.tsv 2>/dev/null
  echo
  echo "== Zygisk 实现探测 =="
  for d in zygisksu ZygiskNext zygisk-next rezygisk Zygisk-Assistant; do
    [ -d "/data/adb/modules/$d" ] && echo "找到: /data/adb/modules/$d"
  done
  command -v ksud >/dev/null 2>&1 && echo "ksud: $(ksud -V 2>/dev/null)"
  echo
  echo "== 内核日志末尾 (dmesg) =="
  dmesg 2>/dev/null | tail -n 40
} > "$SUMMARY" 2>&1

echo "[2/4] 清理日志并启动微信..."
logcat -c 2>/dev/null
am force-stop $PKG
am start -n "$ACT" >/dev/null 2>&1 || monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

echo "[3/4] 等待微信启动（10 秒）..."
sleep 10

echo "[4/4] 导出日志..."
logcat -d > "$LOGFILE" 2>&1

{
  echo
  echo "==== 关键日志（已过滤，末尾 400 行） ===="
  grep -iE 'xfeng|zygisk|lsplant|dexkit|com\.xiao\.feng|fatal|androidruntime|sigsegv|sigabrt|libc|native crash|backtrace|linker' "$LOGFILE" 2>/dev/null | tail -n 400
  echo
  echo "==== 结束 ===="
} >> "$SUMMARY" 2>&1

echo
echo "完成。请把下面两个文件发我："
echo "  $SUMMARY"
echo "  $LOGFILE"
