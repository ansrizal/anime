@file:Suppress("UnstableApiUsage")
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.lagradost.cloudstream3.gradle") {
                useModule("com.github.recloudstream:gradle:-SNAPSHOT")
            }
        }
    }
    plugins {
        id("com.android.application") version "8.7.3" apply false
        id("com.android.library") version "8.7.3" apply false
        id("org.jetbrains.kotlin.android") version "1.9.24" apply false
        id("org.jetbrains.kotlin.jvm") version "1.9.24" apply false
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    versionCatalogs {
        create("libs") {
        }
    }
}

include(":app")
include(":AlqanimeProvider")
include(":AnichinProvider")
include(":Animasu")
include(":AnimeIndo")
include(":AnimeSailProvider")
include(":AnimexinProvider")
include(":AnoboyProvider")
include(":Cinemax21Provider")
include(":DonghubProvider")
include(":DrakorProvider")
include(":DubbindoProvider")
include(":DutamovieProvider")
include(":Filmkita")
include(":Filmlokal")
include(":Hanime")
include(":HidoriStreamProvider")
include(":IdlixProvider")
include(":Indomax")
include(":IndoMax21Provider")
include(":JavHeyProvider")
include(":Kawanfilm")
include(":KlikXXi")
include(":KlikxxiProvider")
include(":KuramanimeProvider")
include(":KuronimeProvider")
include(":LayarAsiaProvider")
include(":LayarKacaProvider")
include(":LayarWarna")
include(":MovieboxProvider")
include(":NekopoiProvider")
include(":Ngefilm")
include(":NgeFilm21Provider")
include(":NimegamiProvider")
include(":NomatProvider")
include(":NontonAnimeIDProvider")
include(":Oploverz")
include(":OppaDramaProvider")
include(":OtakudesuProvider")
include(":Pahe")
include(":Pencurimovie")
include(":PencuriMovieProvider")
include(":Pusatfilm")
include(":Pusatmovie")
include(":Rebahin")
include(":SamehadakuProvider")
include(":Sarangfilm")
include(":Savefilm")
include(":SemiRebahin")
include(":SokujaProvider")
include(":FilmApikProvider")
include(":IndoxxiProvider")
include(":WGFilm21")
include(":MissAVProvider")
