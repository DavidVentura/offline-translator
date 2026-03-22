plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktlint)
  alias(libs.plugins.detekt)
}

android {
  namespace = "dev.davidv.translator"
  compileSdk = 34
  ndkVersion = "28.0.12674087"
  buildToolsVersion = "34.0.0"

  sourceSets {
    getByName("main") {
      aidl.srcDir("src/main/aidl")
    }
    getByName("androidTest") {
      assets {
        srcDirs("src/androidTest/assets")
      }
    }
  }
  defaultConfig {
    applicationId = "dev.davidv.translator"
    minSdk = 28 // iconv functions need 28?
    targetSdk = 34
    versionCode = 9
    versionName = "0.2.4"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      // when building in F-Droid CI, the `cargo` binary is not in path
      // so there's a prebuild step to modify this file and replace "cargo"
      // with the full path to cargo (/home/vagrant/.cargo/bin/..)
      // however, modifying this file leaves the repo in a dirty state
      // which means that the revision in `META-INF/version-control-info.textproto`
      // does not match with the _actual_ commit.
      // Disabling this until I figure out how to put `cargo` in PATH
      // in F-Droid CI
      vcsInfo {
        include = false
      }
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }

    flavorDimensions += listOf("architecture")
    productFlavors {
      create("x86_64") {
        ndk {
          abiFilters += listOf("x86_64") // armeabi-v7a arm64-v8a
        }
        dimension = "architecture"
      }
      create("x86") {
        ndk {
          abiFilters += listOf("x86")
        }
        dimension = "architecture"
      }
      create("aarch64") {
        ndk {
          abiFilters += listOf("arm64-v8a")
        }
        dimension = "architecture"
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
  }
  buildFeatures {
    aidl = true
    compose = true
  }
}

val bindingsRootDir = "src/main/bindings"
val jniLibsDir = "../jniLibs"
val ndk = "${System.getenv("ANDROID_SDK_ROOT")}/ndk/28.0.12674087"
tasks.register("buildBindingsX86_64") {
  group = "build"
  description = "Build Rust bindings library for x86_64"

  doLast {
    exec {
      workingDir = file(bindingsRootDir)
      environment("ANDROID_NDK_ROOT", ndk)
      environment("ANDROID_NDK_HOME", ndk)
      commandLine(
        "cargo",
        "ndk",
        "build",
        "--target",
        "x86_64",
        "--release",
        "--platform",
        "28",
        "--link-libcxx-shared",
        "--output-dir",
        "../jniLibs",
      )
    }
  }
}

tasks.register("buildBindingsAarch64") {
  group = "build"
  description = "Build Rust bindings library for aarch64"

  doLast {
    exec {
      workingDir = file(bindingsRootDir)
      environment("ANDROID_NDK_ROOT", ndk)
      environment("ANDROID_NDK_HOME", ndk)
      commandLine(
        "cargo",
        "ndk",
        "build",
        "--target",
        "arm64-v8a",
        "--release",
        "--platform",
        "28",
        "--link-libcxx-shared",
        "--output-dir",
        "../jniLibs",
      )
    }
  }
}

tasks.register("buildBindingsX86") {
  group = "build"
  description = "Build Rust bindings library for x86"

  doLast {
    exec {
      workingDir = file(bindingsRootDir)
      environment("ANDROID_NDK_ROOT", ndk)
      environment("ANDROID_NDK_HOME", ndk)
      commandLine(
        "cargo",
        "ndk",
        "build",
        "--target",
        "x86",
        "--release",
        "--platform",
        "28",
        "--link-libcxx-shared",
        "--output-dir",
        "../jniLibs",
      )
    }
  }
}

tasks.register("buildBindingsAll") {
  group = "build"
  description = "Build Rust bindings library for all architectures"
  dependsOn("buildBindingsX86_64", "buildBindingsAarch64", "buildBindingsX86")
}

tasks.whenTaskAdded {
  if (name.contains("preAarch64") && name.contains("Build")) {
    dependsOn("buildBindingsAarch64")
  }
  if (name.contains("preX86_64") && name.contains("Build")) {
    dependsOn("buildBindingsX86_64")
  }
  if (name.contains("preX86") && name.contains("Build") && !name.contains("preX86_64")) {
    dependsOn("buildBindingsX86")
  }
}

dependencies {

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.uiautomator)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.kotlinx.serialization.json)
  implementation(project(":app:bergamot"))
  implementation(libs.kotlinx.serialization.json.v162)
  implementation("com.vanniktech:android-image-cropper:4.6.0")
}

ktlint {
  android.set(true)
  ignoreFailures.set(false)
  reporters {
    reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
  }
  filter {
    exclude { element -> element.file.path.contains("generated/") }
  }
}

detekt {
  toolVersion = "1.23.4"
  config.setFrom(file("$projectDir/detekt-config.yml"))
  buildUponDefaultConfig = true
  allRules = false
}

tasks.register("lintAll") {
  dependsOn("ktlintCheck", "detekt")
  description = "Run all lint checks (ktlint and detekt)"
  group = "verification"
}

tasks.register("formatAll") {
  dependsOn("ktlintFormat")
  description = "Format all code using ktlint"
  group = "formatting"
}
