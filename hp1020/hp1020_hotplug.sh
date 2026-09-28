#!/bin/sh
####################################################################
# hp1020_hotplug.sh —— HP1020 固件补灌的 mdev 热插拔钩子（包装版）
#
# 背景：busybox 的 mdev 只执行「第一条」匹配规则，追加一条同规则是
#       无效的。所以本脚本是「接管」固件原有的打印机热插拔处理：
#
#   /etc/mdev.conf 原规则：
#     usb/lp[0-9] 0:0 0660 */sbin/mdev_lp $MDEV $ACTION
#   被改成的规则：
#     usb/lp[0-9] 0:0 0660 */etc/storage/hp1020_hotplug.sh $MDEV $ACTION
#
#   本脚本先调用 /sbin/mdev_lp 保持打印服务(p9100d)原有的启停行为，
#   再针对 HP1020 "断电丢固件" 做补灌。
#
# 打印机断电重启时 USB 会先断开再重新枚举，mdev 会把本脚本叫两次：
#   ACTION=remove -> 固件已随断电丢失，清掉"已灌过"的标记
#   ACTION=add    -> 等 USB 枚举稳定后，后台补灌一次固件
#
# 注意1：mdev 是同步调用，必须尽快返回，所以真正的灌入用 nohup 丢到后台。
# 注意2：mdev 的环境很干净，PATH 可能不全，这里显式设置，命令用绝对路径。
####################################################################

PATH=/usr/bin:/bin:/usr/sbin:/sbin
export PATH

MDEV="$1"
ACTION="$2"
LOG=/tmp/hp1020.log

# 先无条件记一笔，便于确认钩子有没有被 mdev 调用
echo "$(date '+%m-%d %H:%M:%S') [hotplug] MDEV=$MDEV ACTION=$ACTION" >>"$LOG"

# 保留固件原有的打印机热插拔处理（启停 p9100d / lpd）
[ -x /sbin/mdev_lp ] && /sbin/mdev_lp "$MDEV" "$ACTION"

[ "$MDEV" = "usb/lp0" ] || exit 0

if [ "$ACTION" = "remove" ]; then
    rm -f /tmp/hp1020.fw
    rm -rf /tmp/hp1020.lock
    logger -t HP1020 "热插拔: 打印机 USB 断开, 已清除固件标记" 2>/dev/null
    exit 0
fi

logger -t HP1020 "热插拔: 检测到打印机 USB 接入, 8 秒后补灌固件" 2>/dev/null

# add：HP1020 上电时可能出现 add -> remove -> add 的抖动，先等它稳定
/usr/bin/nohup /bin/sh -c 'sleep 8; [ -c /dev/usb/lp0 ] && /etc/storage/print1020.sh load' >/dev/null 2>&1 &

exit 0
