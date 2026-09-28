# ZJS / ACL 打印数据格式笔记（HP LaserJet 1020）

> 来源：foo2zjs 源码（`foo2zjs.c` / `zjs.h` / `jbig.c`），Debian sources 20200505dfsg0-5
> 反向整理，2026-09-28。用于自研 App 时的编码器实现。

## 1. 总体结构

1020 属于 `MODEL_HP1020` 分支，**驱动参数固定为 `-P -z1 -L0`**（源码头部明确标注）。

一个打印作业 = PJL 头 + 若干 ZJ chunk（大端序）：

```
\033%-12345X@PJL JOB
@PJL SET JAMRECOVERY=OFF
@PJL SET DENSITY=<n>
@PJL SET ECONOMODE=ON|OFF
@PJL SET RET=MEDIUM
@PJL INFO STATUS
@PJL USTATUS DEVICE/JOB/PAGE = ON
...
@PJL ENTER LANGUAGE=ACL     ← 之后是 ACL/ZJS 二进制
[ZJ chunk 序列]
\033%-12345X@PJL EOJ
```

（我们灌进打印机的 `sihp1020.dl` 固件本身就是 ACL 语言解释器，固件头也是
`ESC%-12345X@PJL ENTER LANGUAGE=ACL\r\n\0` + `AC DE C0 01` 之类。）

## 2. ZJ chunk 头（16 字节，全部大端）

| 偏移 | 长度 | 字段 | 说明 |
|---|---|---|---|
| 0 | 4 | size | 本记录总长（**含** 16 字节头） |
| 4 | 4 | type | ZJ_TYPE |
| 8 | 4 | items | 本 chunk 内 item 个数（含义随 type 变化） |
| 12 | 2 | reserved | 通常 0；部分机型用于放 `nitems*12` |
| 14 | 2 | signature | **固定 `0x5A5A`（'ZZ'）** |

对应源码函数：`chunk_write(type, items, size, fp)` / `chunk_write_rsvd(...)`。

## 3. ZJ_TYPE 取值

```
ZJT_START_DOC=0   ZJT_END_DOC=1    ZJT_START_PAGE=2  ZJT_END_PAGE=3
ZJT_JBIG_BIH=4    ZJT_JBIG_BID=5   ZJT_END_JBIG=6    ZJT_SIGNATURE=7
ZJT_RAW_IMAGE=8   ← “整平面无压缩”，foo2zjs 从未使用
ZJT_START_PLANE=9 ZJT_END_PLANE=10
ZJT_2600N_PAUSE=11 ZJT_2600N=12
```

## 4. item 记录

`ZJ_ITEM_UINT32`（源码按 C 结构体对齐，**sizeof = 16**）：

| 偏移 | 长度 | 字段 |
|---|---|---|
| 0 | 4 | size（=16） |
| 4 | 2 | item（ZJI_* 编号） |
| 6 | 2 | type（ZJIT_UINT32） |
| 8 | 2 | param |
| 10 | 2 | 对齐填充 |
| 12 | 4 | value |

常用 ZJI_*：`PAGECOUNT=0, DMCOLLATE=1, DMDUPLEX=2, DMPAPER=3, DMCOPIES=4,
DMDEFAULTSOURCE=5, DMMEDIATYPE=6, NBIE=7, RESOLUTION_X=8, RESOLUTION_Y=9,
RASTER_X=?, RASTER_Y=?, OFFSET_X/Y, VIDEO_X/Y/BPP, ECONOMODE, PLANE, RET ...`

START_PAGE 会写入：`NBIE(=1 单色)`、`RESOLUTION_X/Y`、`RASTER_X/Y`、`OFFSET_X/Y`、
`VIDEO_X/Y/BPP`、`DMPAPER`、`DMCOPIES`、`DMDEFAULTSOURCE`、`DMMEDIATYPE` 等。

## 5. 位图数据 = JBIG（T.82）压缩 ★ 关键难点

每个平面（单色 = 1 个平面）的数据按下列 chunk 序列发出：

```
ZJT_JBIG_BIH  size=20   ← 20 字节 BIH 头（含宽高、层数等）
ZJT_JBIG_BID  ...       ← JBIG 算术编码数据，单块 ≤64KB，最后一块补齐到 4 字节对齐
ZJT_END_JBIG  items=0 size=0
（多平面时另有 START_PLANE / END_PLANE 包裹）
```

- `write_plane()` 会校验 `current->len == 20`（BIH 必须是 20 字节）
- 块大小限制 65536（Rick Richardson 的改动），多余部分补 0

**为什么不能用 `ZJT_RAW_IMAGE` 绕过 JBIG**：600dpi 的 A4 单页裸位图 =
4962×7016 bit ≈ **4.36 MB**，而 1020 机身只有 **2 MB RAM**，整页平面塞不进打印机内存。
厂商驱动与 PrintHand 全都做 JBIG 压缩，就是为了把一页压到几十 KB。

## 6. 自研可行性结论（2026-09-28）

| 层 | 工作量 | 风险 |
|---|---|---|
| ZJS/ACL 封装（chunk/item/PJL） | 小（约 200 行） | 低，格式已完全掌握 |
| **JBIG(T.82) 编码器** | **大**：参考实现 `jbig.c` 3288 行 + `jbig_ar.c` 417 行（算术编码器 + 上下文模型） | **高**：算错一位整页花；本机无 C 编译器、无安卓设备，只能靠"发一页→看纸"迭代 |

参考实现可直连下载（`foo2zjs.rkkda.com` 走代理 502 不通，用 Debian sources）：

```
https://sources.debian.org/data/main/f/foo2zjs/20200505dfsg0-5/foo2zjs.c
https://sources.debian.org/data/main/f/foo2zjs/20200505dfsg0-5/zjs.h
https://sources.debian.org/data/main/f/foo2zjs/20200505dfsg0-5/jbig.c
https://sources.debian.org/data/main/f/foo2zjs/20200505dfsg0-5/jbig_ar.c
```
