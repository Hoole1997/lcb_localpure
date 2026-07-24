# Music Aggregation SDK

`music-sdk` 将 Jamendo 与 Audius 映射为统一的曲目、艺人、专辑和歌单模型，并提供并发聚合、平台隔离、分页、筛选、多个 Key 轮换、限流冷却、失效 Key 摘除及健康状态查询。

## 接入

业务模块添加依赖：

```kotlin
implementation(project(":music-sdk"))
```

在应用的 composition root 创建单例。凭据应由 CI、Remote Config 或你的后端下发，不要提交到仓库：

```kotlin
val musicSdk = MusicSdkFactory.create(
    MusicSdkConfig(
        jamendoClientIds = listOf(jamendoKey1, jamendoKey2),
        audiusCredentials = listOf(
            AudiusCredential(audiusKey1, audiusBearer1),
            AudiusCredential(audiusKey2, audiusBearer2),
        ),
    ),
)
```

然后从协程调用统一接口：

```kotlin
val page = musicSdk.searchTracks(query = "jazz", limit = 20)
val hot = musicSdk.trendingTracks(limit = 20)
val filtered = musicSdk.tracks(
    query = TrackQuery(genre = "electronic", sort = MusicSort.LATEST),
    page = PageRequest(offset = 20, limit = 20),
)
val track = musicSdk.getTrack(MusicPlatform.AUDIUS, "trackId")
val similar = musicSdk.similarTracks(MusicPlatform.JAMENDO, "trackId")
val artists = musicSdk.searchArtists("artist name", PageRequest(limit = 20))
val artist = track.artist // 稳定的 platform + artistId + name 引用
val artistDetails = musicSdk.getArtistDetails(MusicPlatform.AUDIUS, "artistId")
val artistTracks = musicSdk.artistTracks(
    MusicPlatform.AUDIUS,
    "artistId",
    sort = MusicSort.POPULAR,
)
val artistAlbums = musicSdk.artistAlbums(MusicPlatform.JAMENDO, "artistId")
val artistPlaylists = if (artistDetails.capabilities.supports(MusicArtistFeature.PLAYLISTS)) {
    musicSdk.artistPlaylists(MusicPlatform.AUDIUS, "artistId")
} else {
    null
}
val albums = musicSdk.searchAlbums("album name")
val albumTracks = musicSdk.albumTracks(MusicPlatform.JAMENDO, "albumId")
val playlists = musicSdk.searchPlaylists("playlist name")
val playlistTracks = musicSdk.playlistTracks(MusicPlatform.AUDIUS, "playlistId")
val providerStates = musicSdk.checkHealth() // 主动探测
val cachedStates = musicSdk.health()         // 仅读取本地快照

// Remote Config 或自有后端返回新配置后原子热替换，不需要重建 SDK。
musicSdk.updateCredentials(
    MusicSdkCredentials(
        jamendoClientIds = latestJamendoClientIds,
        audiusCredentials = latestAudiusCredentials,
    ),
)
```

`MusicPage` 提供 `offset`、`limit`、`hasMore`、`nextOffset` 和可空的 `totalCount`。Jamendo 支持 `fullcount` 时会返回精确总数；Audius 很多接口不提供总数，因此 `totalCount == null`，业务层应始终使用 SDK 返回的 `nextOffset` 继续翻页，不能自行用 `items.size` 推算。Audius 在过滤不可播放歌曲后，`nextOffset` 会按上游实际消费数量推进，避免重复页和空页死循环。

`TrackQuery.platform` 为空时会并发聚合所有平台；指定平台时直接使用该平台的原生 offset 分页。支持关键字、genre、mood、artistId、collectionId、时长区间以及相关度/热门/最新排序；平台不支持的筛选项会安全忽略。

Audius 的专辑与歌单共用 playlist 实体，SDK 根据 `is_album` 归一化。Audius 的歌单曲目接口不接受分页参数，SDK 获取后在本地稳定切片；Jamendo 则使用 `positionbetween` 分页。

## 歌手页能力

`getArtist()` 保留为轻量基础信息接口；歌手主页应使用 `getArtistDetails()`。详情模型统一提供头像、背景图、简介、地区、统计、标签、加入日期和社交链接，并通过 `MusicArtistCapabilities` 明确平台是否支持对应栏目：

- Audius：歌曲、专辑、歌单、背景图、简介、地区、粉丝/关注统计和社交链接。
- Jamendo：歌曲、专辑、简介、地区、音乐标签和网站；不支持歌手创建歌单及统一粉丝统计。

歌曲中的 `MusicArtistRef` 是进入歌手页的唯一可靠入口。不要用 `artistName` 再次搜索，因为平台内可能存在同名歌手。

## 故障策略

- `401/403`：认为 Key 无效，本次进程永久摘除。
- `429`：遵循 `Retry-After`，没有该响应头时使用配置的冷却时间。
- 网络错误或 `5xx`：Key 临时冷却并切换下一个 Key。
- 其他 `4xx`：视为调用参数问题，不切换 Key，防止无效请求放大。
- 聚合查询使用 supervisor 隔离平台；只要一个平台成功，业务层就能获得结果。

Audius 会将 API Key 与同一组 Bearer Token 一起轮换，且任何日志都不会输出原始凭据。需要注意：Firebase Remote Config 和 APK 都不是密钥保险库，有能力分析客户端的人仍可取得下发值。如 Bearer 具有收藏、上传等写权限，应改由可信后端代持；SDK 不接收 API Secret。

## Firebase Remote Config 格式

Application 层可监听以下字段：

- `music_jamendo_client_ids`：JSON 字符串数组，例如 `["id1","id2"]`；也兼容 `id1,id2`。
- `music_audius_credentials`：推荐使用 `[{"apiKey":"key1","bearerToken":"token1"}]`。
- `music_audius_api_keys` + `music_audius_bearer_tokens`：兼容旧版逗号列表，两个列表按下标成对。

字段缺失或解析失败时保留该平台当前凭据；明确下发 `[]` 才会停用对应平台。
本地凭据也可以全部为空，SDK 会以不可用健康状态启动，待远程凭据到达后再启用 Provider。
