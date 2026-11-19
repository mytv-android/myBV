# HDR Vivid 清晰度（qn 129）

对应 issue：[mytv-android/myBV#7](https://github.com/mytv-android/myBV/issues/7)（测试视频 `BV1FykdYzELQ`）。

代码位置：

| 项 | 位置 |
| --- | --- |
| 清晰度枚举 | [player/shared/.../player/entity/Resolution.kt](../player/shared/src/main/kotlin/com/github/mytv/bv/player/entity/Resolution.kt) |
| bili-api 侧的清晰度枚举 | [bili-api/.../http/entity/video/PlayUrlResponse.kt](../bili-api/src/main/kotlin/dev/aaa1115910/biliapi/http/entity/video/PlayUrlResponse.kt)（`VideoQuality`） |
| HTTP 通道的 fnval | [bili-api/.../repositories/VideoPlayRepository.kt](../bili-api/src/main/kotlin/dev/aaa1115910/biliapi/repositories/VideoPlayRepository.kt)（`HTTP_FNVAL`） |
| gRPC 通道的 fnval | 同上（`GRPC_FNVAL`，比 `HTTP_FNVAL` 多 16384） |
| playurl 请求的最高档位 | 同上（`REQUEST_TOP_QN`） |
| 数据源（Web / App）设置 | [app/ui/.../feature/settings/NetworkSettings.kt](../app/ui/src/main/kotlin/com/github/mytv/bv/ui/feature/settings/NetworkSettings.kt)（`settings_net_api`） |
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

## 三个必须同时成立的条件

1. **识别**：`Resolution.fromCode(129)` 必须返回独立档位。129 不在枚举里时会走
   `getOrDefault(R1080P)` 被当成 1080P，用户在清晰度列表里看不到 HDR Vivid。
2. **请求顶档**：playurl 的 `qn` 要报 129。档位码越大越高，报 127 等于声明
   「我最高只要 8K」，服务端就不会把 HDR Vivid 片源一起下发。
3. **fnval 带 HDR Vivid 位**：`fnval` 必须含 **16384**。只报 `4048` 时接口
   `vod_info.stream_list` / `accept_quality` 最高只到 125（HDR 真彩），
   报 `4048 | 16384 = 20432` 才会出现
   `quality=129 / format="hdflv2" / description="HDR Vivid"`。

前两条在 1cc9a84c 里就做了，但第三条漏了 —— 这就是「设置里能选 HDR Vivid、
播放时却切不过去」的原因：识别和请求都对，接口侧就是不下发 129。

另外，满足这三条还不够：**数据源必须是「App 端」**（设置 → 网络 → 接口偏好）。
网页端走的 HTTP 接口在 fnval 上就被卡死，见下节。

## 通道：HTTP 拿不到 HDR Vivid，只有 gRPC 能

实测（2026-11-19，`BV1FykdYzELQ`，avid `113707162011107` / cid `27507229004`）：

- **HTTP 播放接口对 fnval 有硬上限。** `fnval >= 4096` 一律回 `-400 请求错误`。
  逐位试过：合法位只有 `1/2/16/64/128/256/512/1024/2048`，
  `4`、`8`、`32` 和一切 `>= 4096` 的组合都是 `-400`。
  换成 app 签名（5 组 appkey：android / android_hd / android_tv_yst / 4409e2ce8ffd12b8 /
  bb3101000e232e27）、加 access_key、换 `/x/player/wbi/playurl`、`/x/tv/playurl`、
  `/pgc/player/web/v2/playurl`、`/pgc/player/api/playurl`，结果完全一样。
  **结论：HTTP 通道结构上不可能下发 129，与账号是不是大会员无关。**
- **gRPC `PlayViewUnite` 带 16384 就能拿到。** 请求
  `POST https://grpc.biliapi.net/bilibili.app.playerunite.v1.Player/PlayViewUnite`
  （`content-type: application/grpc+proto`），`vod.fnval = 20432` 时
  `vod_info.stream_list[0]` 即 `quality=129 / format="hdflv2" / description="HDR Vivid"`；
  `fnval = 4048` 时最高是 125。多次重试结果稳定，与 `qn` 取 129/80/64 无关。
- **gRPC 只认 access_key，不认 cookie。** 鉴权头是 `authorization: identify_v1 $accessKey`
  加 `x-bili-metadata-bin`。实测在同一个调用上加 `cookie` / `Cookie` 头（完整网页 cookie
  或只带 `SESSDATA`），返回与匿名完全一致，cookie 被忽略。
  所以要用 HDR Vivid，必须在设置里把**数据源切到「App 端」并用「App 扫码登录」登录**。
- **匿名 gRPC 只发 480P。** 换 buvid3 / buvid4 / 随机 buvid、带不带 `x-bili-mid`、
  `qn` 报多少，实际返回的 `quality` 都是 32。但 `stream_list` 仍然给出完整档位表
  （含 129）—— 即「看得到选不了」。这也是 App 源未登录时仍要回落到 HTTP
  （`try_look` 能拿 1080P）的原因。

## 播放侧

HDR Vivid 是 HEVC Main 10 + CUVA 动态元数据，走的就是普通 H.265 Main 10 解码通路，
`VideoCodec.HEVC`（`hev1` / `hvc1` 前缀）即可匹配，不需要新增编码枚举。
输出面沿用默认的 `SurfaceView` 直出即可让显示管线进 HDR 模式
（见 [video-render-mode.md](video-render-mode.md)）。
