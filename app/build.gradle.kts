import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.room)
    alias(libs.plugins.google.ksp)
    alias(libs.plugins.google.gms.google.services)
    alias(libs.plugins.google.firebase.crashlytics)
}

@Suppress("UNCHECKED_CAST")
fun extraMap(name: String): Map<String, Any?> {
    return (rootProject.findProperty(name) as? Map<*, *>)
        ?.mapKeys { it.key.toString() }
        ?: emptyMap()
}

fun Map<String, Any?>.stringValue(name: String, defaultValue: String = ""): String {
    return this[name]?.toString() ?: defaultValue
}

fun Map<String, Any?>.intValue(name: String, defaultValue: Int): Int {
    return when (val value = this[name]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: defaultValue
        else -> defaultValue
    }
}

fun booleanGradleProperty(name: String, defaultValue: Boolean): Boolean {
    return providers.gradleProperty(name).orNull?.let { value ->
        when {
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            else -> defaultValue
        }
    } ?: defaultValue
}

fun secretValue(name: String): String {
    return rootProject.findProperty(name)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(name)?.trim().orEmpty()
}

/** Converts project metadata into a portable artifact file-name segment. */
fun String.toArtifactNameSegment(fallback: String): String {
    return trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_', '.', '-')
        .ifEmpty { fallback }
}

/**
 * 客户端音乐平台凭据会随 APK/AAB 分发，本质上可被提取；这里作为 Remote Config 未下发时的
 * 可用性兜底。CI 同名环境变量仍可覆盖内置值，便于密钥轮换而无需修改业务代码。
 */
val builtInMusicJamendoClientIds = "617fcfac,20b76c90"
val builtInMusicAudiusApiKeys =
    "c53b3d1db49ff769804066f25b888bd357e834c4,707211e30a70f8c6f31d396d3cdff01069399fd7"
val builtInMusicAudiusBearerTokens =
    "EjrggU1WfiJnQS1Ivn9PJEOkFnZvcmOmOo-r2kwtlOI=,x0EOhgSCurVAb2rRSVHDb6H2FdhHcNU9NNVO3JqijbA="

fun musicCredential(name: String, builtInValue: String): String =
    providers.environmentVariable(name).orNull?.trim()?.takeIf(String::isNotEmpty)
        ?: builtInValue

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun resolveSigningFile(path: String) = file(path).takeIf { it.isAbsolute } ?: rootProject.file(path)

fun googleServicesPackageName(flavor: String): String? {
    val servicesFile = file("src/$flavor/google-services.json")
    if (!servicesFile.isFile) return null

    val json = groovy.json.JsonSlurper().parse(servicesFile) as? Map<*, *> ?: return null
    val clients = json["client"] as? List<*> ?: return null
    val clientInfo = (clients.firstOrNull() as? Map<*, *>)?.get("client_info") as? Map<*, *> ?: return null
    val androidInfo = clientInfo["android_client_info"] as? Map<*, *> ?: return null
    return androidInfo["package_name"]?.toString()
}

val appConfig = extraMap("app")
val analyticsConfig = extraMap("analytics")
val legalConfig = extraMap("legal")

val resolvedVersionName = appConfig.stringValue("versionName", "1.0.0")
val googleReleaseKeystorePath = secretValue("ANDROID_SIGNING_STORE_FILE")
val googleReleaseKeystoreFile = if (googleReleaseKeystorePath.isNotEmpty()) {
    resolveSigningFile(googleReleaseKeystorePath)
} else {
    file("src/google/google-release.keystore")
}
val googleReleaseStorePassword = secretValue("ANDROID_SIGNING_STORE_PASSWORD").ifEmpty { "google123456" }
val googleReleaseKeyAlias = secretValue("ANDROID_SIGNING_KEY_ALIAS").ifEmpty { "google" }
val googleReleaseKeyPassword = secretValue("ANDROID_SIGNING_KEY_PASSWORD").ifEmpty { "google123456" }
val hasGoogleReleaseSigning = googleReleaseKeystoreFile.isFile &&
    googleReleaseKeystoreFile.length() > 0L &&
    googleReleaseStorePassword.isNotEmpty() &&
    googleReleaseKeyAlias.isNotEmpty() &&
    googleReleaseKeyPassword.isNotEmpty()
val googleReleaseArtifactTaskPrefixes = listOf("assemble", "bundle", "package", "publish")
val requiresGoogleReleaseSigning = gradle.startParameter.taskNames.any { taskName ->
    val simpleTaskName = taskName.substringAfterLast(':').lowercase()
    // Metadata helper tasks mention GoogleRelease but do not produce a signed artifact.
    simpleTaskName.contains("googlerelease") &&
        googleReleaseArtifactTaskPrefixes.any(simpleTaskName::startsWith)
}
val artifactProjectName = rootProject.name.toArtifactNameSegment("android_app")
val artifactVersionName = resolvedVersionName.toArtifactNameSegment("unknown")
// UTC keeps artifact names unambiguous across local machines and GitHub-hosted runners.
val artifactTimestampUtc = DateTimeFormatter
    .ofPattern("yyyyMMdd_HHmmss")
    .withZone(ZoneOffset.UTC)
    .format(Instant.now())
val googleReleaseAabName =
    "${artifactProjectName}_google_release_v${artifactVersionName}_${artifactTimestampUtc}_UTC.aab"
val releaseMinifyEnabled = booleanGradleProperty("android.release.minifyEnabled", true)
val releaseShrinkResourcesEnabled = booleanGradleProperty("android.release.shrinkResourcesEnabled", false)
val releaseOptimizeEnabled = booleanGradleProperty("android.release.optimizeEnabled", releaseMinifyEnabled)
val releaseDefaultProguardFile = if (releaseMinifyEnabled && releaseOptimizeEnabled) {
    "proguard-android-optimize.txt"
} else {
    "proguard-android.txt"
}
val fallbackApplicationId = appConfig.stringValue("applicationId", "com.example.lcb.app")
val googleApplicationId = googleServicesPackageName("google") ?: fallbackApplicationId
val localApplicationId = googleServicesPackageName("local") ?: fallbackApplicationId

android {
    namespace = "com.example.lcb.app"
    compileSdk = 36

    defaultConfig {
        minSdk = appConfig.intValue("minSdk", 26)
        targetSdk = appConfig.intValue("targetSdk", 35)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val defaultChannel = analyticsConfig.stringValue("defaultUserChannel", "default")
        buildConfigField("String", "DEFAULT_USER_CHANNEL", "\"$defaultChannel\"")
        buildConfigField(
            "String",
            "MUSIC_JAMENDO_CLIENT_IDS",
            buildConfigString(musicCredential("MUSIC_JAMENDO_CLIENT_IDS", builtInMusicJamendoClientIds)),
        )
        buildConfigField(
            "String",
            "MUSIC_AUDIUS_API_KEYS",
            buildConfigString(musicCredential("MUSIC_AUDIUS_API_KEYS", builtInMusicAudiusApiKeys)),
        )
        buildConfigField(
            "String",
            "MUSIC_AUDIUS_BEARER_TOKENS",
            buildConfigString(musicCredential("MUSIC_AUDIUS_BEARER_TOKENS", builtInMusicAudiusBearerTokens)),
        )
        buildConfigField("String", "PRIVACY_POLICY_URL", buildConfigString(legalConfig.stringValue("privacyPolicyUrl")))
        buildConfigField("String", "TERMS_OF_SERVICE_URL", buildConfigString(legalConfig.stringValue("termsOfServiceUrl")))

    }

    flavorDimensions += "channel"

    productFlavors {
        create("google") {
            dimension = "channel"
            applicationId = googleApplicationId
            versionCode = appConfig.intValue("versionCode", 1)
            versionName = resolvedVersionName
        }

        create("local") {
            dimension = "channel"
            applicationId = localApplicationId
            versionCode = appConfig.intValue("versionCode", 1)
            versionName = "$resolvedVersionName-local"
            isDefault = true
        }
    }

    signingConfigs {
        create("googleRelease") {
            if (hasGoogleReleaseSigning) {
                storeFile = googleReleaseKeystoreFile
                storePassword = googleReleaseStorePassword
                keyAlias = googleReleaseKeyAlias
                keyPassword = googleReleaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isShrinkResources = false
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = releaseMinifyEnabled
            isShrinkResources = releaseMinifyEnabled && releaseShrinkResourcesEnabled
            if (hasGoogleReleaseSigning || requiresGoogleReleaseSigning) {
                signingConfig = signingConfigs.getByName("googleRelease")
            }
            if (requiresGoogleReleaseSigning && !hasGoogleReleaseSigning) {
                throw GradleException(
                    "Missing google release signing config. Ensure app/src/google/google-release.keystore exists or set " +
                        "ANDROID_SIGNING_STORE_FILE, ANDROID_SIGNING_STORE_PASSWORD, ANDROID_SIGNING_KEY_ALIAS, " +
                        "and ANDROID_SIGNING_KEY_PASSWORD."
                )
            }
            proguardFiles(
                getDefaultProguardFile(releaseDefaultProguardFile),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.version",
            )
        }
    }
}

tasks.register("printGoogleReleaseVersionName") {
    group = "help"
    description = "Prints the versionName used for google release builds."
    doLast {
        println(resolvedVersionName)
    }
}

tasks.register("printGoogleReleaseAabName") {
    group = "help"
    description = "Prints the expected output file name for google release AAB builds."
    doLast {
        println(googleReleaseAabName)
    }
}

configurations.configureEach {
    exclude(group = "com.google.firebase", module = "protolite-well-known-types")
}

// Room schema 纳入版本控制，升级数据库版本时由编译器校验 AutoMigration。
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.config)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics)
    implementation(libs.gson)
    implementation(libs.glide)
    implementation("androidx.cardview:cardview:1.0.0")

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.room:room-testing:2.8.4")

//    implementation(project(":core"))
    implementation(project(":metrics"))
    implementation(project(":music-sdk"))
    implementation("com.github.toukaremax:core:1.0.15")
}
