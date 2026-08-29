plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.gradle")
}

val cloudstreamApiVersion = "-SNAPSHOT"

cloudstream {
    setRepo("https://github.com/ansrizal/anime")
    authors = listOf("Ans Rizal")
    description = "CloudStream 3 streaming provider extension module for NontonAnimeIDProvider"
    version = 1
}

android {
    namespace = "com.ansrizal.anime.nontonanimeidprovider"
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

    // CloudStream Core SDK library dependency
    compileOnly("com.github.recloudstream.cloudstream:library:$cloudstreamApiVersion")

    // HTTP / Network & Scraping (NiceHttp & OkHttp)
    implementation("com.github.Blatzar:NiceHttp:0.4.11")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // HTML / DOM Parsing
    implementation("org.jsoup:jsoup:1.18.1")

    // JSON Serialization & Utils
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.google.code.gson:gson:2.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // JavaScript Engine (Mozilla Rhino for JS extractors)
    implementation("org.mozilla:rhino:1.7.15")
}
