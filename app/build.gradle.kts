plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktlint)
  alias(libs.plugins.detekt)
}

val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)
val defaultDevAbis = listOf("arm64-v8a", "x86_64")

android {
  namespace = "dev.davidv.translator"
  compileSdk = 34
  ndkVersion = "28.0.13004108"
  buildToolsVersion = "34.0.0"

  sourceSets {
    getByName("main") {
      aidl.srcDir("src/main/aidl")
      java.srcDir(layout.buildDirectory.dir("generated/source/uniffi/kotlin"))
    }
    getByName("androidTest") {
      assets {
        srcDirs("src/androidTest/assets")
      }
    }
  }
  defaultConfig {
    applicationId = "dev.davidv.translator"
    minSdk = 21
    targetSdk = 34
    versionCode = 14
    versionName = "0.3.3"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  splits {
    abi {
      isEnable = true
      reset()
      val targetAbi = project.findProperty("targetAbi")?.toString()
      if (targetAbi != null) {
        include(targetAbi)
      } else {
        include(*defaultDevAbis.toTypedArray())
      }
      isUniversalApk = false
    }
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

  applicationVariants.all {
    outputs.all {
      val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
      val abi = output.getFilter(com.android.build.OutputFile.ABI)
      if (abi != null) {
        output.versionCodeOverride = defaultConfig.versionCode!! * 10 + (abiCodes[abi] ?: 0)
      }
    }
  }
}

val bindingsRootDir = file("src/main/bindings")
val bindingsBindgenRootDir = file("bindings-bindgen")
val jniLibsRootDir = file("src/main/jniLibs")
val androidSdkRoot =
  System.getenv("ANDROID_SDK_ROOT")
    ?: System.getenv("ANDROID_HOME")
    ?: throw GradleException("ANDROID_SDK_ROOT or ANDROID_HOME must be set")
val ndk = "$androidSdkRoot/ndk/28.0.13004108"
val bindingsAndroidApi = 28
val onnxRuntimeRootDir = file("../third_party/onnxruntime")
val onnxRuntimeSourceFingerprint =
  providers
    .exec {
      workingDir = onnxRuntimeRootDir
      commandLine(
        "bash",
        "-lc",
        """
        set -euo pipefail
        if [ ! -e .git ]; then
          echo missing
          exit 0
        fi
        git rev-parse HEAD
        git stash create || true
        git status --short --untracked-files=normal
        git submodule status --recursive
        git submodule foreach --quiet --recursive 'printf "%s\n" "${'$'}displaypath"; git rev-parse HEAD; git stash create || true; git status --short --untracked-files=normal'
        """.trimIndent(),
      )
    }.standardOutput
    .asText
    .map(String::trim)

fun onnxRuntimeBuildDir(abi: String) = file("${layout.buildDirectory.asFile.get()}/onnxruntime/$abi")

fun onnxRuntimeConfigDir(abi: String) = File(onnxRuntimeBuildDir(abi), "Release")

fun onnxRuntimeSharedLibrary(abi: String) = File(onnxRuntimeConfigDir(abi), "libonnxruntime.so")

fun jniLibAbiDir(abi: String) = File(jniLibsRootDir, abi)

fun cmakePathRemapFlags(): String =
  listOf(
    "-ffile-prefix-map=${rootProject.projectDir.absolutePath}=.",
    "-fdebug-prefix-map=${rootProject.projectDir.absolutePath}=.",
  ).joinToString(" ")

fun cargoEncodedRustflags(abi: String? = null): String {
  val base =
    listOf(
      "--remap-path-prefix=${rootProject.projectDir.absolutePath}=.",
      "--remap-path-prefix=/home/vagrant/.cargo=/",
      "--remap-path-prefix=/usr/local/cargo=/",
    )
  if (abi == null) return base.joinToString("\u001f")
  val rtArch =
    when (abi) {
      "arm64-v8a" -> "aarch64-android"
      "armeabi-v7a" -> "arm-android"
      "x86" -> "i686-android"
      "x86_64" -> "x86_64-android"
      else -> error("Unknown abi $abi")
    }
  val rtLib = "$ndk/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/19/lib/linux/libclang_rt.builtins-$rtArch.a"
  return (
    base +
      listOf(
        "-C",
        "link-arg=-Wl,--threads=1",
        "-C",
        "link-arg=-Wl,--sort-section=name",
        "-C",
        "link-arg=-Wl,--sort-common",
        "-C",
        "link-arg=$rtLib",
      )
  ).joinToString("\u001f")
}

val abiToTaskSuffix =
  mapOf(
    "arm64-v8a" to "Aarch64",
    "armeabi-v7a" to "ArmeabiV7a",
    "x86_64" to "X86_64",
    "x86" to "X86",
  )

val abiToCargoTarget =
  mapOf(
    "arm64-v8a" to "arm64-v8a",
    "armeabi-v7a" to "armeabi-v7a",
    "x86_64" to "x86_64",
    "x86" to "x86",
  )

val verifyOnnxRuntimeSources =
  tasks.register("verifyOnnxRuntimeSources") {
    group = "verification"
    description = "Verify the ONNX Runtime source tree is already present"
    doLast {
      val buildScript = onnxRuntimeRootDir.resolve("tools/ci_build/build.py")
      if (!buildScript.isFile) {
        error(
          "ONNX Runtime sources are missing at ${onnxRuntimeRootDir.absolutePath}. " +
            "Initialize submodules before running Gradle.",
        )
      }
    }
  }

val abiToOnnxRuntimeTask =
  abiToCargoTarget.keys.associateWith { abi ->
    val taskSuffix = abiToTaskSuffix.getValue(abi)
    val buildTask =
      tasks.register("buildOnnxRuntime$taskSuffix", Exec::class) {
        group = "build"
        description = "Build ONNX Runtime for $abi"
        dependsOn(verifyOnnxRuntimeSources)
        workingDir = onnxRuntimeRootDir
        // The ONNX Runtime checkout includes paths that Gradle cannot fingerprint reliably on CI.
        // Fingerprint the git state instead so local edits still invalidate the native build.
        inputs.property("onnxRuntimeSourceFingerprint", onnxRuntimeSourceFingerprint)
        inputs.property("abi", abi)
        inputs.property("androidApi", bindingsAndroidApi)
        inputs.property("androidSdkRoot", androidSdkRoot)
        inputs.property("androidNdkRoot", ndk)
        inputs.property("cmakeCppStandard", "20")
        inputs.property("cmakePathRemapFlags", cmakePathRemapFlags())
        outputs.file(onnxRuntimeSharedLibrary(abi))
        commandLine(
          "python3",
          "tools/ci_build/build.py",
          "--build_dir=${onnxRuntimeBuildDir(abi).absolutePath}",
          "--config=Release",
          "--update",
          "--build",
          "--targets",
          "onnxruntime",
          "--skip_tests",
          "--parallel",
          "--android",
          "--android_abi=$abi",
          "--android_api=$bindingsAndroidApi",
          "--android_sdk_path=$androidSdkRoot",
          "--android_ndk_path=$ndk",
          "--android_cpp_shared",
          "--build_shared_lib",
          "--disable_ml_ops",
          "--disable_generation_ops",
          "--no_kleidiai",
          "--use_xnnpack",
          "--no_sve",
          "--cmake_extra_defines",
          "CMAKE_CXX_STANDARD=20",
          "CMAKE_CXX_STANDARD_REQUIRED=ON",
          "CMAKE_CXX_EXTENSIONS=OFF",
          "CMAKE_C_FLAGS=${cmakePathRemapFlags()}",
          "CMAKE_CXX_FLAGS=${cmakePathRemapFlags()}",
          "onnxruntime_USE_ARM_NEON_NCHWC=ON",
          "--skip_submodule_sync",
        )
      }

    tasks.register("packageOnnxRuntime$taskSuffix", Copy::class) {
      group = "build"
      description = "Copy libonnxruntime.so for $abi into jniLibs"
      dependsOn(buildTask)
      from(onnxRuntimeSharedLibrary(abi))
      into(jniLibAbiDir(abi))
    }
  }

val abiToBindingsTask =
  abiToCargoTarget.mapValues { (abi, cargoTarget) ->
    val taskSuffix = abiToTaskSuffix.getValue(abi)
    tasks.register("buildBindings$taskSuffix") {
      group = "build"
      description = "Build Rust bindings library for $abi"
      dependsOn(abiToOnnxRuntimeTask.getValue(abi))
      inputs.file(bindingsRootDir.resolve("Cargo.toml"))
      inputs.file(bindingsRootDir.resolve("Cargo.lock"))
      inputs.file(bindingsRootDir.resolve(".cargo/config.toml"))
      inputs.dir(bindingsRootDir.resolve("src"))
      inputs.file(onnxRuntimeSharedLibrary(abi))
      inputs.property("cargoTarget", cargoTarget)
      inputs.property("androidApi", bindingsAndroidApi)
      inputs.property("androidNdkRoot", ndk)
      inputs.property("cargoEncodedRustflags", cargoEncodedRustflags(abi))
      outputs.file(File(jniLibAbiDir(abi), "libbindings.so"))
      outputs.file(File(jniLibAbiDir(abi), "libc++_shared.so"))

      doLast {
        exec {
          workingDir = bindingsRootDir
          environment("ANDROID_NDK_ROOT", ndk)
          environment("ANDROID_NDK_HOME", ndk)
          environment("ORT_LIB_LOCATION", onnxRuntimeConfigDir(abi).absolutePath)
          environment("ORT_PREFER_DYNAMIC_LINK", "1")
          environment("CARGO_ENCODED_RUSTFLAGS", cargoEncodedRustflags(abi))
          commandLine(
            "cargo",
            "ndk",
            "build",
            "--lib",
            "--target",
            cargoTarget,
            "--release",
            "--platform",
            bindingsAndroidApi.toString(),
            "--link-libcxx-shared",
            "--output-dir",
            "../jniLibs",
          )
        }
      }
    }
  }

tasks.register("buildOnnxRuntimeAll") {
  group = "build"
  description = "Build ONNX Runtime for all architectures"
  dependsOn(abiToOnnxRuntimeTask.values.toList())
}

tasks.register("buildBindingsAll") {
  group = "build"
  description = "Build Rust bindings library for all architectures"
  dependsOn(abiToBindingsTask.values.toList())
}

val targetAbi = project.findProperty("targetAbi")?.toString()
val selectedAbis =
  if (targetAbi != null) {
    listOf(targetAbi)
  } else {
    defaultDevAbis
  }
val bindingsTasks = selectedAbis.mapNotNull { abiToBindingsTask[it] }

val bindgenHostBinary = File(bindingsBindgenRootDir, "target/release/uniffi-bindgen")

val buildUniffiBindgen =
  tasks.register("buildUniffiBindgen", Exec::class) {
    group = "build"
    description = "Build the host uniffi-bindgen binary"
    workingDir = bindingsBindgenRootDir
    inputs.file(bindingsBindgenRootDir.resolve("Cargo.toml"))
    inputs.dir(bindingsBindgenRootDir.resolve("src"))
    outputs.file(bindgenHostBinary)
    commandLine("cargo", "build", "--release")
  }

val generatedBindingsDir = layout.buildDirectory.dir("generated/source/uniffi/kotlin")

val bindgenSourceAbi = selectedAbis.first()
val bindgenSourceLib = File(jniLibAbiDir(bindgenSourceAbi), "libbindings.so")

val generateUniffiBindings =
  tasks.register("generateUniffiBindings", Exec::class) {
    group = "build"
    description = "Generate Kotlin bindings from the compiled libbindings.so"
    dependsOn(buildUniffiBindgen)
    dependsOn(abiToBindingsTask.getValue(bindgenSourceAbi))
    workingDir = bindingsRootDir
    inputs.file(bindgenHostBinary)
    inputs.file(bindgenSourceLib)
    outputs.dir(generatedBindingsDir)
    doFirst {
      val outDir = generatedBindingsDir.get().asFile
      outDir.deleteRecursively()
      outDir.mkdirs()
    }
    commandLine(
      bindgenHostBinary.absolutePath,
      "generate",
      "--library",
      bindgenSourceLib.absolutePath,
      "--language",
      "kotlin",
      "--out-dir",
      generatedBindingsDir.get().asFile.absolutePath,
      "--no-format",
      "--metadata-no-deps",
    )
  }

tasks.named("preBuild") {
  dependsOn(bindingsTasks)
  dependsOn(generateUniffiBindings)
}

dependencies {

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.material)
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
  implementation(libs.kotlinx.serialization.json.v162)
  implementation("com.vanniktech:android-image-cropper:4.6.0")
  implementation("net.java.dev.jna:jna:5.14.0@aar")
}

ktlint {
  android.set(true)
  ignoreFailures.set(false)
  reporters {
    reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
  }
  filter {
    exclude { it.file.path.contains("generated/") }
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
