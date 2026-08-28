plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.gradle")
}

val cloudstreamApiVersion = "-SNAPSHOT"

version = 1

cloudstream {
    description = "Sarangfilm - Streaming Movie and TV Series"
    language = "id"
    authors = listOf("ansrizal")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama",
    )
}

android {
    namespace = "com.ansrizal.anime.sarangfilm"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = true
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xskip-metadata-version-check",
            "-Xskip-prerelease-check"
        )
    }
}

dependencies {
    val cloudstreamApiVersion = "-SNAPSHOT"
    compileOnly("com.github.recloudstream.cloudstream:library:$cloudstreamApiVersion")
    implementation("com.github.Blatzar:NiceHttp:0.4.11")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.2")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.mozilla:rhino:1.9.1")
}
