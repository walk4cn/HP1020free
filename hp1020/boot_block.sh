### >>> HP1020 firmware auto-upload (start) >>>
# HP1020 宿主型打印机固件每次通电都要重灌，详见 /etc/storage/print1020.sh
# 1) 接管 mdev 的打印机热插拔规则（/etc 是内存盘，每次开机都要重打）
#    注意：busybox mdev 只执行第一条匹配规则，所以必须「替换」而不是「追加」
if [ -x /etc/storage/hp1020_hotplug.sh ]; then
    grep -q 'hp1020_hotplug.sh' /etc/mdev.conf 2>/dev/null || \
        sed -i 's#/sbin/mdev_lp#/etc/storage/hp1020_hotplug.sh#' /etc/mdev.conf
fi
# 2) 守护进程（兜底轮询，防止漏事件）
/etc/storage/print1020.sh watch >/dev/null 2>&1 &
# 3) 开机立即补灌一次（此时打印机可能早已通电）
( sleep 12; /etc/storage/print1020.sh load ) >/dev/null 2>&1 &
### <<< HP1020 firmware auto-upload (end) <<<
