import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish.base")
}

//https://issuetracker.google.com/issues/226095015
com.android.tools.analytics.AnalyticsSettings.optedIn = false

android {
  namespace = "app.cash.paykit.analytics"
  compileSdk = 31

  defaultConfig {
    minSdk = 21
    targetSdk = 31

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles.add(file("consumer-rules.pro"))
  }

  resourcePrefix = "paykit_analytics_"

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

  lint {
    abortOnError = true
    htmlReport = true
    warningsAsErrors = true
    checkAllWarnings = true
    baseline = file("lint-baseline.xml")
  }
}

apply(from = "../versions.gradle.kts")
val junit_version: String by extra
val mockk_version: String by extra
val robolectric_version: String by extra

dependencies {

  testImplementation("junit:junit:$junit_version")
  testImplementation("io.mockk:mockk:$mockk_version")

  // Robolectric environment.
  testImplementation("org.robolectric:robolectric:$robolectric_version")
}

mavenPublishing {
  // AndroidMultiVariantLibrary(publish a sources jar, publish a javadoc jar)
  configure(AndroidSingleVariantLibrary("release", true, true))
}
