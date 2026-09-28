#!/bin/sh
####################################################################
# print1020.sh  —— HP LaserJet 1020 / 1020 Plus 固件自动补灌
#
# 原理：HP 1020 是宿主型(GDI/ZJS)打印机，机身只有 32KB ROM，
#       固件不固化在机器里，每次通电必须由外部把 sihp1020.dl
#       灌进打印机内存，断电即清空。路由器上的打印服务(p9100d)
#       只转发数据、不会补固件，所以打印机一断电就成了空壳。
#       本脚本负责在打印机每次重新接入 USB 后自动补灌一次。
#
# 校验：灌完后用 PJL 命令 @PJL INFO ID 询问打印机，
#       能收到 "HP LaserJet 1020" 应答才算真正成功
#       （没有固件的打印机是不会应答的）。
#       注意：usblp 同一时刻只允许一个写者，若设备正被占用
#       （有打印任务），探测会返回"未知"，此时不重复灌固件。
#
# 用法：
#   print1020.sh load   立即补灌一次（带存活校验）
#   print1020.sh probe  只做存活探测
#   print1020.sh watch  常驻守护(兜底轮询 + mdev 补丁自检)
#
# 部署：/etc/storage/print1020.sh   权限 0755
#       配套 /etc/storage/sihp1020.dl 与 /etc/storage/hp1020_hotplug.sh
#       开机启动见 /etc/storage/started_script.sh 开头的补丁块
####################################################################

FW="/etc/storage/sihp1020.dl"
DEV="/dev/usb/lp0"
SYSDEV="/sys/class/usb/lp0/device"
FLAG="/tmp/hp1020.fw"          # 本次 USB 会话是否已灌过固件
LOCK="/tmp/hp1020.lock"        # 灌入互斥锁(带僵尸自愈)
WLOCK="/tmp/hp1020.watch"      # 守护进程单例锁
IDF="/tmp/hp1020.id"           # PJL 应答缓存
LOG="/tmp/hp1020.log"
MDEVCONF="/etc/mdev.conf"
HOOKFILE="/etc/storage/hp1020_hotplug.sh"
LOCK_MAX=180                   # 锁最长存活秒数, 超过视为残留

log() {
    echo "$(date '+%m-%d %H:%M:%S') $*" >>"$LOG"
    logger -t HP1020 "$*" 2>/dev/null
}

devnum() {
    cat "$SYSDEV/../devnum" 2>/dev/null
}

# 返回 0 = 已有活的补灌任务在跑; 1 = 可以继续
lock_busy() {
    [ -d "$LOCK" ] || return 1
    lp=$(cat "$LOCK/pid" 2>/dev/null)
    if [ -n "$lp" ] && kill -0 "$lp" 2>/dev/null; then
        return 0
    fi
    # PID 已不存在 -> 残留锁
    rm -rf "$LOCK"
    return 1
}

# ---------- 存活探测 ----------
# 返回: 0=有应答(固件已加载)  1=无应答(固件缺失)  2=设备被占用,状态未知
probe_ok() {
    [ -c "$DEV" ] || return 1
    # 先试探能否写打开：usblp 同一时刻只允许一个写者
    ( : >"$DEV" ) 2>/dev/null || return 2
    rm -f "$IDF"
    printf '\033%%-12345X@PJL INFO ID\n\033%%-12345X' >"$DEV" 2>/dev/null || return 2
    ( dd if="$DEV" bs=1 count=200 of="$IDF" 2>/dev/null ) &
    bp=$!
    sleep 3
    kill $bp 2>/dev/null
    [ -s "$IDF" ] && grep -q 'LaserJet' "$IDF" 2>/dev/null
}

# ---------- 灌固件（灌完必须通过存活校验） ----------
load_fw() {
    [ -f "$FW" ] || { log "错误: 缺少固件文件 $FW"; return 1; }
    lock_busy && return 0
    mkdir "$LOCK" 2>/dev/null || return 0
    echo $$ >"$LOCK/pid"
    echo $(date +%s) >"$LOCK/ts"

    a=1
    while [ $a -le 3 ]; do
        if [ ! -c "$DEV" ]; then
            log "第${a}轮: $DEV 不存在"
        elif cat "$FW" >"$DEV" 2>/dev/null; then
            k=1
            busy=0
            while [ $k -le 4 ]; do
                probe_ok
                pr=$?
                if [ $pr -eq 0 ]; then
                    touch "$FLAG"
                    log "补灌成功并通过存活校验 (USB devnum $(devnum), 第${a}轮下发/第${k}次探测)"
                    rm -rf "$LOCK"
                    return 0
                fi
                [ $pr -eq 2 ] && busy=$((busy + 1))
                k=$((k + 1))
                sleep 2
            done
            # 设备一直被别人占用(有打印任务)时无法判定，按已灌入处理，避免反复重灌
            if [ $busy -ge 3 ]; then
                touch "$FLAG"
                log "第${a}轮: 固件已下发, 但探测时设备被占用(可能有打印任务), 按已灌入处理"
                rm -rf "$LOCK"
                return 0
            fi
            log "第${a}轮: 固件已下发但打印机无应答"
        else
            log "第${a}轮: 写入 $DEV 失败"
        fi
        a=$((a + 1))
        sleep 2
    done

    rm -rf "$LOCK"
    log "固件补灌失败, 已放弃本轮"
    return 1
}

# ---------- 守护 ----------
watch_loop() {
    mkdir "$WLOCK" 2>/dev/null || { log "守护进程已在运行, 退出"; exit 0; }
    log "===== watch 守护启动 ====="
    n=0
    while true; do
        if [ -c "$DEV" ]; then
            [ -f "$FLAG" ] || load_fw
        else
            if [ -f "$FLAG" ]; then
                rm -f "$FLAG"
                log "打印机已离线, 清除标记等待重新接入"
            fi
        fi

        n=$((n + 1))
        # 启动后头一分钟每轮都自检(尽快把 mdev 补丁打上), 之后每 60 秒一次
        if [ $n -le 10 ] || [ $((n % 10)) -eq 0 ]; then
            # mdev 只执行第一条匹配规则, 所以是「替换」/sbin/mdev_lp 而不是追加
            if [ -x "$HOOKFILE" ]; then
                grep -q 'hp1020_hotplug.sh' "$MDEVCONF" 2>/dev/null || {
                    sed -i 's#/sbin/mdev_lp#/etc/storage/hp1020_hotplug.sh#' "$MDEVCONF"
                    log "mdev 热插拔补丁丢失, 已自动补上"
                }
            fi
            # 清理可能残留的僵尸锁
            if [ -d "$LOCK" ]; then
                lt=$(cat "$LOCK/ts" 2>/dev/null)
                now=$(date +%s)
                [ -n "$lt" ] && [ $((now - lt)) -gt $LOCK_MAX ] && rm -rf "$LOCK" && log "清理超时残留锁"
            fi
        fi
        sleep 6
    done
}

case "$1" in
    load) load_fw ;;
    probe)
        probe_ok
        case $? in
            0) echo "打印机有应答: $(tr -d '\r\n\f' <"$IDF")" ;;
            2) echo "设备被占用(可能有打印任务在跑), 状态未知" ;;
            *) echo "打印机无应答(固件可能未加载)" ;;
        esac
        ;;
    watch | "") watch_loop ;;
    *) echo "用法: $0 {load|probe|watch}" ;;
esac
