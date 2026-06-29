import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Читаем ключи Supabase из local.properties (в .gitignore), чтобы не хардкодить их в коде.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, fallback: String = ""): String =
    (localProps.getProperty(key) ?: System.getenv(key) ?: fallback)

android {
    namespace = "kz.sultan.spendlimit"
    compileSdk = 34

    defaultConfig {
        applicationId = "kz.sultan.spendlimit"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Доступны в коде как BuildConfig.SUPABASE_URL / BuildConfig.SUPABASE_ANON_KEY
        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY")}\"")

        // Ключ Anthropic для голосового NLU. Лежит в local.properties (в .gitignore),
        // в исходник/репозиторий не попадает. Модель — Haiku по умолчанию (дёшево, быстро).
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${secret("ANTHROPIC_API_KEY")}\"")
        buildConfigField("String", "ANTHROPIC_MODEL", "\"${secret("ANTHROPIC_MODEL", "claude-haiku-4-5")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Экспорт схемы Room в app/schemas — нужен для сверки рукописных миграций
// с тем, что Room ожидает от @Entity, и для будущих автотестов миграций.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// --- Офлайн-модель Vosk для wake-word ---
// Модель тяжёлая (~45 MB) и в git не хранится (.gitignore). Эта задача один раз скачивает
// её и распаковывает в assets, чтобы сборка была воспроизводима из репозитория и в CI без
// ручной докладки файлов. VoskModelProvider читает модель из assets/vosk-model-small-ru/.
val voskModelVersion = "0.22"
val voskModelName = "vosk-model-small-ru-$voskModelVersion"
val voskModelUrl = "https://alphacephei.com/vosk/models/$voskModelName.zip"
val voskAssetsDir = layout.projectDirectory.dir("src/main/assets/vosk-model-small-ru")
// Маркер версии. Точечное имя — aapt не пакует dotfile'ы в APK, но Gradle читает его для up-to-date.
val voskMarker = voskAssetsDir.file(".vosk-model-version")

val fetchVoskModel = tasks.register("fetchVoskModel") {
    description = "Скачивает офлайн-модель Vosk ($voskModelName) и распаковывает её в assets (один раз)."
    group = "build setup"
    outputs.dir(voskAssetsDir)
    // Готово, если маркер уже содержит нужную версию — повторно не качаем и не распаковываем.
    outputs.upToDateWhen {
        voskMarker.asFile.run { exists() && readText().trim() == voskModelVersion }
    }
    doLast {
        val assetsDir = voskAssetsDir.asFile
        val cacheZip = layout.buildDirectory.file("vosk/$voskModelName.zip").get().asFile
        cacheZip.parentFile.mkdirs()

        if (!cacheZip.exists() || cacheZip.length() == 0L) {
            logger.lifecycle("Качаю Vosk-модель: $voskModelUrl (~45 MB)…")
            var current = voskModelUrl
            var done = false
            for (hop in 0 until 5) {
                val conn = (uri(current).toURL().openConnection() as java.net.HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 30_000
                    readTimeout = 120_000
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    current = conn.getHeaderField("Location") ?: error("Редирект без Location при скачивании модели")
                    conn.disconnect()
                    continue
                }
                if (code != 200) error("Скачивание модели вернуло HTTP $code: $current")
                conn.inputStream.use { input -> cacheZip.outputStream().use { input.copyTo(it) } }
                done = true
                break
            }
            if (!done) error("Слишком много редиректов при скачивании модели Vosk")
        }

        // Перераспаковка с нуля: чистим папку и срезаем верхний каталог архива (vosk-model-small-ru-0.22/).
        assetsDir.deleteRecursively()
        assetsDir.mkdirs()
        copy {
            from(zipTree(cacheZip)) {
                eachFile {
                    relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
                }
                includeEmptyDirs = false
            }
            into(assetsDir)
        }
        voskMarker.asFile.writeText(voskModelVersion)
        logger.lifecycle("Vosk-модель распакована в ${assetsDir.relativeTo(rootDir)}")
    }
}

// Цепляем только к слиянию ассетов (merge*Assets), а не к preBuild — чтобы юнит-тесты,
// которым модель не нужна, не тянули 45 MB зря.
tasks.matching { it.name.matches(Regex("merge.*Assets")) }.configureEach {
    dependsOn(fetchVoskModel)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Supabase: синхронизация и аутентификация
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.auth)
    implementation(libs.ktor.client.okhttp)

    // Vosk: офлайн-распознавание речи для wake-word (фоновая голосовая активация)
    implementation(libs.vosk.android)

    testImplementation("junit:junit:4.13.2")
}
