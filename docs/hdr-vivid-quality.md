# HDR Vivid 清晰度（qn 129）

对应 issue：[mytv-android/myBV#7](https://github.com/mytv-android/myBV/issues/7)（测试视频 `BV1FykdYzELQ`）。

代码位置：

| 项 | 位置 |
| --- | --- |
| 清晰度枚举 | [player/shared/.../player/entity/Resolution.kt](../player/shared/src/main/kotlin/com/github/mytv/bv/player/entity/Resolution.kt) |
| bili-api 侧的清晰度枚举 | [bili-api/.../http/entity/video/PlayUrlResponse.kt](../bili-api/src/main/kotlin/dev/aaa1115910/biliapi/http/entity/video/PlayUrlResponse.kt)（`VideoQuality`） |
| playurl 请求的最高档位 | [bili-api/.../repositories/VideoPlayRepository.kt](../bili-api/src/main/kotlin/dev/aaa1115910/biliapi/repositories/VideoPlayRepository.kt)（`REQUEST_TOP_QN`） |
| 清晰度选择 / 回退 | [app/shared/.../repository/PlayUrlResolver.kt](../app/shared/src/main/kotlin/com/github/mytv/bv/repository/PlayUrlResolver.kt)（`getAvailableQualities` / `selectQuality`） |
| 回归测试 | [app/shared/src/test/.../repository/PlayUrlResolverQualityTest.kt](../app/shared/src/test/kotlin/com/github/mytv/bv/repository/PlayUrlResolverQualityTest.kt) |

## B 站清晰度码（qn）全表

| qn | 名称 | 备注 |
| --- | --- | --- |
| 6 | 240P 极速 | |
| 16 | 360P 流畅 | |
| 32 | 480P 标清 | |
| 64 | 720P 准高清 | |
| 74 | 720P60 | |
| 80 | 1080P 高清 | |
| 100 | 智能修复 | 仅 App 源，Web 源不下发 |
| 112 | 1080P+ 高码率 | |
| 116 | 1080P60 | |
| 120 | 4K 超高清 | |
| 125 | HDR 真彩 | 即 HDR10，HEVC Main 10 |
| 126 | 杜比视界 | |
| 127 | 8K 超高清 | |
| **129** | **HDR Vivid** | 菁彩 HDR（CUVA 标准），**档位高于 8K** |

`fnval = 4048` 已经覆盖全部格式位（DASH / HDR / 4K / 杜比 / 8K / AV1），
HDR Vivid 不占额外的 fnval 位；网页播放器（`player/main/core.*.js`）的
`VIDEO_QN` 枚举里根本没有 129，所以网页端从来不出这个档位。

## 两个必须同时成立的条件

1. **识别**：`Resolution.fromCode(129)` 必须返回独立档位。原来 129 不在枚举里，
   会走 `getOrDefault(R1080P)` 被当成 1080P，用户在清晰度列表里看不到 HDR Vivid。
2. **请求**：playurl 的 `qn` 要报顶档 129。档位码越大越高，报 127 等于声明
   「我最高只要 8K」，服务端就不会把 HDR Vivid 片源一起下发，只会给 HDR 真彩。

官方 App 的行为可以佐证第 2 点：BiliRoamingX/BiliRoamingX#759 记录，
在播放器里固定清晰度后会丢掉 HDR Vivid 选项、降级成 HDR 真彩 ——
即客户端没有按顶档请求时，服务端自动兜底降级。

## 实测记录（2026-11-19，未登录账号）

`GET https://api.bilibili.com/x/player/playurl?avid=113707162011107&cid=27507229004&fnval=4048&fnver=0&fourk=1`

- `fnval` 逐位试：`0/1/64/128/256/512/1024/2048` 都只给 mp4 720P/360P；
  `16` 给 DASH 但不含 125；`4048` 才出现 125（HDR 真彩）。
  `fnval >= 4096` 直接 `-400 请求错误`。
- 请求的 `qn`（0/32/64/80/125/126/127/129/130）**不影响** `accept_quality`
  和 `dash.video` 列表，只影响返回的 `quality` 字段。
- 未登录时 `support_formats` 只有 `125,120,116,80,64,32,16`，
  `dash.video` 只有 `80,64,32,16`（125 列了但不发流）。
  129 / 126 / 127 都需要登录（大概率还要大会员 + 设备支持）才出现，
  所以这一条只能在有权限的账号上复验。

## 播放侧

HDR Vivid 是 HEVC Main 10 + CUVA 动态元数据，走的就是普通 H.265 Main 10 解码通路，
`VideoCodec.HEVC`（`hev1` / `hvc1` 前缀）即可匹配，不需要新增编码枚举。
输出面沿用默认的 `SurfaceView` 直出即可让显示管线进 HDR 模式
（见 [video-render-mode.md](video-render-mode.md)）。
