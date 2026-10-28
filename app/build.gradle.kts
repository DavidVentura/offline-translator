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
    minSdk = 23
    targetSdk = 34
    versionCode = 32
    versionName = "0.8.7"

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
    buildConfig = true
  }

  androidResources {
    generateLocaleConfig = true
  }

  lint {
    // Translations are managed in Weblate and land incrementally, so partial
    // locales are expected; completeness is tracked there, not by the build.
    disable += "MissingTranslation"
    // minSdk is 23 and nothing at runtime catches an API 24+ call there until a user on old
    // Android hits it, so release builds refuse to ship one.
    fatal += "NewApi"
    // The pre-push hook gates on exactly what release builds refuse, without the other checks'
    // run time or their advisory errors.
    if (providers.gradleProperty("lintNewApiOnly").isPresent) {
      checkOnly += "NewApi"
    }
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
val bindingsAndroidApi = 23

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

val cargoProfileArgs = listOf("--release")

val abiToBindingsTask =
  abiToCargoTarget.mapValues { (abi, cargoTarget) ->
    val taskSuffix = abiToTaskSuffix.getValue(abi)
    tasks.register("buildBindings$taskSuffix") {
      group = "build"
      description = "Build Rust bindings library for $abi"
      inputs.file(bindingsRootDir.resolve("Cargo.toml"))
      inputs.file(bindingsRootDir.resolve("Cargo.lock"))
      inputs.file(bindingsRootDir.resolve(".cargo/config.toml"))
      inputs.dir(bindingsRootDir.resolve("src"))

      // Local-dev convenience: auto-discover any `path = "..."`
      // dependency in Cargo.toml and watch its `src/` so editing the
      // path-deped crate (typically translator-rs in a sibling
      // checkout) invalidates this task without a manual cache-bust.
      // CI uses git deps — the parsed paths don't exist there, so the
      // inputs.dir calls are skipped and behaviour is unchanged.
      val cargoTomlText = bindingsRootDir.resolve("Cargo.toml").readText()
      // Matches both `path = "..."` (own-line dep) and the inline
      // `name = { path = "..." }` form used by [patch] overrides; the
      // leading anchor still skips commented-out lines.
      val pathDepRegex = Regex("""(?m)^\s*(?:[A-Za-z0-9_-]+\s*=\s*\{\s*)?path\s*=\s*"([^"]+)"""")
      pathDepRegex.findAll(cargoTomlText).forEach { match ->
        val raw = match.groupValues[1]
        val resolved =
          if (File(raw).isAbsolute) File(raw) else bindingsRootDir.resolve(raw).normalize()
        val srcDir = resolved.resolve("src")
        if (srcDir.exists()) {
          inputs.dir(srcDir)
          val depCargo = resolved.resolve("Cargo.toml")
          if (depCargo.exists()) inputs.file(depCargo)
        }
        resolved.resolve("crates").listFiles()?.forEach { crate ->
          val crateSrc = crate.resolve("src")
          if (crateSrc.exists()) {
            inputs.dir(crateSrc)
            val crateCargo = crate.resolve("Cargo.toml")
            if (crateCargo.exists()) inputs.file(crateCargo)
          }
        }
      }
      inputs.property("cargoTarget", cargoTarget)
      inputs.property("androidApi", bindingsAndroidApi)
      inputs.property("androidNdkRoot", ndk)
      inputs.property("cargoEncodedRustflags", cargoEncodedRustflags(abi))
      inputs.property("cargoProfile", "release")
      outputs.file(File(jniLibAbiDir(abi), "libbindings.so"))
      outputs.file(File(jniLibAbiDir(abi), "libc++_shared.so"))

      doLast {
        exec {
          workingDir = bindingsRootDir
          environment("ANDROID_NDK_ROOT", ndk)
          environment("ANDROID_NDK_HOME", ndk)
          environment("CARGO_ENCODED_RUSTFLAGS", cargoEncodedRustflags(abi))
          // CMake cross-compile contract for slimt-sys: parent build owns
          // toolchain selection, the -sys crate just forwards these.
          environment(
            "CMAKE_TOOLCHAIN_FILE",
            File(ndk, "build/cmake/android.toolchain.cmake").absolutePath,
          )
          environment("ANDROID_ABI", abi)
          environment("ANDROID_PLATFORM", "android-$bindingsAndroidApi")
          environment("CMAKE_GENERATOR", "Ninja")
          commandLine(
            "cargo",
            "ndk",
            "build",
            "--lib",
            "--target",
            cargoTarget,
            *cargoProfileArgs.toTypedArray(),
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

tasks.register("buildBindingsAll") {
  group = "build"
  description = "Build Rust bindings library for all architectures"
  dependsOn(abiToBindingsTask.values.toList())
}

// Separate cdylib for the on-device ONNX->MNN migration converter. It links the
// MNN converter (full protobuf), which cannot share a binary with slimt's
// sentencepiece (protobuf-lite), so it must NOT be part of libbindings.so.
val converterRootDir = file("src/main/model-converter")

val abiToConverterTask =
  abiToCargoTarget.mapValues { (abi, cargoTarget) ->
    val taskSuffix = abiToTaskSuffix.getValue(abi)
    tasks.register("buildModelConverter$taskSuffix") {
      group = "build"
      description = "Build the ONNX->MNN converter library for $abi"
      inputs.file(converterRootDir.resolve("Cargo.toml"))
      inputs.file(converterRootDir.resolve(".cargo/config.toml"))
      inputs.dir(converterRootDir.resolve("src"))

      val cargoTomlText = converterRootDir.resolve("Cargo.toml").readText()
      val pathDepRegex = Regex("""(?m)^\s*(?:[A-Za-z0-9_-]+\s*=\s*\{\s*)?path\s*=\s*"([^"]+)"""")
      pathDepRegex.findAll(cargoTomlText).forEach { match ->
        val raw = match.groupValues[1]
        val resolved =
          if (File(raw).isAbsolute) File(raw) else converterRootDir.resolve(raw).normalize()
        val srcDir = resolved.resolve("src")
        if (srcDir.exists()) {
          inputs.dir(srcDir)
          val depCargo = resolved.resolve("Cargo.toml")
          if (depCargo.exists()) inputs.file(depCargo)
        }
        resolved.resolve("crates").listFiles()?.forEach { crate ->
          val crateSrc = crate.resolve("src")
          if (crateSrc.exists()) {
            inputs.dir(crateSrc)
            val crateCargo = crate.resolve("Cargo.toml")
            if (crateCargo.exists()) inputs.file(crateCargo)
          }
        }
      }
      inputs.property("cargoTarget", cargoTarget)
      inputs.property("androidApi", bindingsAndroidApi)
      inputs.property("androidNdkRoot", ndk)
      inputs.property("cargoEncodedRustflags", cargoEncodedRustflags(abi))
      inputs.property("cargoProfile", "release")
      // libc++_shared.so is produced/owned by the bindings task; this task only
      // owns its own .so to avoid overlapping gradle outputs.
      outputs.file(File(jniLibAbiDir(abi), "libmodel_converter.so"))

      doLast {
        exec {
          workingDir = converterRootDir
          environment("ANDROID_NDK_ROOT", ndk)
          environment("ANDROID_NDK_HOME", ndk)
          environment("CARGO_ENCODED_RUSTFLAGS", cargoEncodedRustflags(abi))
          environment(
            "CMAKE_TOOLCHAIN_FILE",
            File(ndk, "build/cmake/android.toolchain.cmake").absolutePath,
          )
          environment("ANDROID_ABI", abi)
          environment("ANDROID_PLATFORM", "android-$bindingsAndroidApi")
          environment("CMAKE_GENERATOR", "Ninja")
          commandLine(
            "cargo",
            "ndk",
            "build",
            "--lib",
            "--target",
            cargoTarget,
            *cargoProfileArgs.toTypedArray(),
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

tasks.register("buildModelConverterAll") {
  group = "build"
  description = "Build the ONNX->MNN converter library for all architectures"
  dependsOn(abiToConverterTask.values.toList())
}

val targetAbi = project.findProperty("targetAbi")?.toString()
val selectedAbis =
  if (targetAbi != null) {
    listOf(targetAbi)
  } else {
    defaultDevAbis
  }
val bindingsTasks = selectedAbis.mapNotNull { abiToBindingsTask[it] }
val converterTasks = selectedAbis.mapNotNull { abiToConverterTask[it] }

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
    inputs.file(bindingsRootDir.resolve("uniffi.toml"))
    outputs.dir(generatedBindingsDir)
    doFirst {
      val outDir = generatedBindingsDir.get().asFile
      outDir.deleteRecursively()
      outDir.mkdirs()
    }
    commandLine(
      bindgenHostBinary.absolutePath,
      "generate",
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
  dependsOn(converterTasks)
  dependsOn(generateUniffiBindings)
}

dependencies {

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.lifecycle.runtime.compose)
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
  implementation("com.github.yalantis:ucrop:2.2.11")
  implementation("net.java.dev.jna:jna:5.14.0@aar")
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  detektPlugins(project(":detekt-rules"))
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

tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detektHardcodedStrings") {
  description = "Fails the build on any hardcoded user-facing string (NoHardcodedUiString)."
  group = "verification"
  buildUponDefaultConfig = false
  config.setFrom(file("$projectDir/detekt-hardcoded-strings.yml"))
  setSource(files("src/main/java"))
  include("**/*.kt")
  reports {
    html.required.set(false)
    xml.required.set(false)
    txt.required.set(false)
    sarif.required.set(false)
    md.required.set(false)
  }
}

tasks.register("lintAll") {
  dependsOn("ktlintCheck", "detekt", "detektHardcodedStrings")
  description = "Run all lint checks (ktlint and detekt)"
  group = "verification"
}

tasks.register("formatAll") {
  dependsOn("ktlintFormat")
  description = "Format all code using ktlint"
  group = "formatting"
}
