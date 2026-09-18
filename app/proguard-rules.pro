# LCB application R8 rules.
#
# 这里只保留代码扫描后确认存在的反射、序列化和三方可选依赖契约。Activity、Service、
# Fragment、自定义 View、Room、Media3、Glide、Firebase 与 OkHttp 已由 AAPT 或
# 各依赖的 consumer rules 覆盖，禁止再使用 `-keep class com.example.lcb.** { *; }` 这类
# 会让业务代码整体失去压缩和混淆效果的规则。

# 保留行号供 Crashlytics 使用 mapping.txt 还原 Release 堆栈，同时隐藏真实源码文件名。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# LcbApp 由 Manifest 反射创建，需保留类名。
-keep class com.example.lcb.app.LcbApp { *; }

# PlayerActivity 将播放队列交给 Gson 反射序列化，并由 PlayerViewModel 从 Intent/SavedState
# 恢复。只保留实际参与 JSON 的字段名；类名、普通方法和其他播放器代码仍可正常混淆。
-keepclassmembers,allowoptimization class com.example.lcb.app.player.PlayerTrack {
    <fields>;
}
-keepclassmembers,allowoptimization class com.example.lcb.music.model.MusicArtistRef {
    <fields>;
}
