# music-sdk 对平台响应使用 JsonObject 显式取字段，不依赖 DTO 字段名反射，因此这里不能
# keep 整个 model/provider 包。保留公共泛型与嵌套类型元数据即可支持 MusicPage<T>、
# TypeToken 及 Kotlin/Java 调用方在 Release 中读取正确的类型信息。
-keepattributes Signature,InnerClasses,EnclosingMethod

# MusicPlatform 是 SDK 对外稳定的来源标识，宿主会通过 name/valueOf 把它持久化到数据库、
# Intent 或 MediaSession。保持枚举常量名称跨版本稳定；其余 SDK 实现仍允许压缩和混淆。
-keep enum com.example.lcb.music.model.MusicPlatform { *; }
