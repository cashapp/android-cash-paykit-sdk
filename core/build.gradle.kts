plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.google.devtools.ksp").version("1.6.21-1.0.5")
  id("project-report")
  id("com.vanniktech.maven.publish.base")
}

android {
  namespace = "app.cash.paykit.core"
  compileSdk = 31

  defaultConfig {
    minSdk = 21
    targetSdk = 31

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("consumer-rules.pro")
    resValue("string", "cap_version", version.toString())
  }

  buildTypes {
    getByName("release") {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  kotlinOptions {
    jvmTarget = "1.8"
  }

  resourcePrefix("cap_")

  lint {
    abortOnError = true
    htmlReport = true
    warningsAsErrors = true
    checkAllWarnings = true
    baseline = file("lint-baseline.xml")
  }

  testOptions {
    unitTests.apply {
      isReturnDefaultValues = true
      isIncludeAndroidResources = true
    }
  }
}

apply(from = "../versions.gradle.kts")
val junit_version: String by extra
val mockk_version: String by extra
val robolectric_version: String by extra
val moshi_version: String by extra
val kotlinx_date_version: String by extra
val lifecycle_version: String by extra
val startup_version: String by extra
val okhttp_version: String by extra
val google_truth_version: String by extra
val junit_androidx_version: String by extra
val mockwebserver_version: String by extra
val coroutines_test_version: String by extra

dependencies {

  // We want to lock this dependency at a lower than latest version to not force transitive updates onto merchants.
  //noinspection GradleDependency
  ksp("com.squareup.moshi:moshi-kotlin-codegen:$moshi_version")
  //noinspection GradleDependency
  implementation("com.squareup.moshi:moshi-kotlin:$moshi_version")

  // KotlinX Dates.
  implementation("org.jetbrains.kotlinx:kotlinx-datetime:$kotlinx_date_version")

  // Provides a lifecycle for the whole application process.
  implementation("androidx.lifecycle:lifecycle-process:$lifecycle_version")

  // AndroidX Startup.
  implementation("androidx.startup:startup-runtime:$startup_version")

  implementation("com.squareup.okhttp3:okhttp:$okhttp_version")

  implementation(project(":analytics-core"))

  // TEST RELATED.
  testImplementation("junit:junit:$junit_version")
  testImplementation("io.mockk:mockk:$mockk_version")
  testImplementation("com.google.truth:truth:$google_truth_version")
  androidTestImplementation("androidx.test.ext:junit-ktx:$junit_androidx_version")
  testImplementation("com.squareup.okhttp3:mockwebserver:$mockwebserver_version")

  // Robolectric environment.
  testImplementation("org.robolectric:robolectric:$robolectric_version")

  // Coroutines test support.
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutines_test_version")

  //Test helpers for Lifecycle runtime
  testImplementation("androidx.lifecycle:lifecycle-runtime-testing:$lifecycle_version")
}

mavenPublishing {
  // AndroidMultiVariantLibrary(publish a sources jar, publish a javadoc jar)
  configure(com.vanniktech.maven.publish.AndroidSingleVariantLibrary("release", true, true))
}
