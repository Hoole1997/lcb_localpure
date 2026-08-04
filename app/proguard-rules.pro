# LCB application R8 rules.
#
# 这里只保留代码扫描后确认存在的反射、序列化和三方可选依赖契约。Activity、Service、
# Fragment、自定义 View、Room、Media3、Glide、Firebase、OkHttp 与广告 SDK 已由 AAPT 或
# 各依赖的 consumer rules 覆盖，禁止再使用 `-keep class com.example.lcb.** { *; }` 这类
# 会让业务代码整体失去压缩和混淆效果的规则。

# 保留行号供 Crashlytics 使用 mapping.txt 还原 Release 堆栈，同时隐藏真实源码文件名。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# LcbApp 由 Manifest 创建，同时覆写渠道 Launcher SDK 的加固/native 钩子。两个渠道依赖
# 暴露的是 pre-R8 符号，因此该适配层的类名、覆写方法和成员都不能再次改名或优化掉。
-keep class com.example.lcb.app.LcbApp { *; }

# PlayerActivity 将播放队列交给 Gson 反射序列化，并由 PlayerViewModel 从 Intent/SavedState
# 恢复。只保留实际参与 JSON 的字段名；类名、普通方法和其他播放器代码仍可正常混淆。
-keepclassmembers,allowoptimization class com.example.lcb.app.player.PlayerTrack {
    <fields>;
}
-keepclassmembers,allowoptimization class com.example.lcb.music.model.MusicArtistRef {
    <fields>;
}

# 插屏来源以 enum.name 放入 Intent，并可能经过系统进程恢复后再读取；保持其名称契约稳定。
-keep enum com.example.lcb.app.utils.InterstitialAdPlacement { *; }

# 下列类是广告/Launcher AAR 为可选运行环境预留的接口，本项目未接入对应 Unity、快手数据
# 控制器和 Joda Convert 模块。仅压制这些已核实的可选引用，不隐藏其他缺失类告警。
-dontwarn com.kwad.sdk.datacollection.KsSafetyPrivateDataController
-dontwarn com.unity3d.player.**
-dontwarn org.joda.convert.FromString
-dontwarn org.joda.convert.ToString
