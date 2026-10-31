import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// 签名信息：仓库不包含任何密钥。
// 优先级：本地 keystore.properties > 环境变量 > 无（release 自动回退 debug 签名）。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val releaseStorePath = keystoreProps.getProperty("storeFile") ?: System.getenv("EORI_STORE_FILE")
val releaseStoreFile = releaseStorePath?.let { rootProject.file(it) }?.takeIf { it.exists() }
val releaseStorePassword = keystoreProps.getProperty("storePassword") ?: System.getenv("EORI_STORE_PASSWORD")
val releaseKeyAlias = keystoreProps.getProperty("keyAlias") ?: System.getenv("EORI_KEY_ALIAS")
val releaseKeyPassword = keystoreProps.getProperty("keyPassword") ?: System.getenv("EORI_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.ddl.eori"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ddl.eori"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 没有密钥时回退 debug 签名，保证 clone 下来就能出包
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "META-INF/*.kotlin_module"
        )
    }

    lint {
        abortOnError = false
        // release 打包不再跑 lintVital（本机 2 核环境下它会长时间阻塞打包）
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // 受限沙箱内 $HOME 不可写，把 Robolectric 的下载/缓存目录重定向到工程内
                val robolectricDir = rootProject.file(".robolectric").apply { mkdirs() }
                val tmpDir = rootProject.file(".tmp").apply { mkdirs() }
                it.systemProperty("robolectric.dependency.dir", robolectricDir.absolutePath)
                it.systemProperty("robolectric.offline", "false")
                it.systemProperty("java.io.tmpdir", tmpDir.absolutePath)
                it.maxHeapSize = "1g"
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation("androidx.datastore:datastore-preferences:1.1.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Robolectric：在 JVM 上启动真实 Activity，验证 UI 渲染与全链路（本机无 KVM，无法跑模拟器）
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.room:room-testing:2.7.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
