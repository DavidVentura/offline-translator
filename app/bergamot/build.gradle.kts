plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
}

val defaultAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val targetAbi = project.findProperty("targetAbi")?.toString()

android {
  namespace = "dev.davidv.bergamot"
  compileSdk = 34
  ndkVersion = "28.0.12674087"
  buildToolsVersion = "34.0.0"

  defaultConfig {

    minSdk = 21
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("consumer-rules.pro")
    ndk {
      abiFilters += if (targetAbi != null) listOf(targetAbi) else defaultAbis
    }
    externalNativeBuild {
      cmake {
        cppFlags("-std=c++17")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
      externalNativeBuild {
        cmake {
          arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
        }
      }
    }
  }
  externalNativeBuild {
    cmake {
      path("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
  }
}

dependencies {

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
}
