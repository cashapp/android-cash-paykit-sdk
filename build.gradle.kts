import com.vanniktech.maven.publish.SonatypeHost

// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {

  extra.apply {
    set("versions", mapOf(
        "minSdk" to 21,
        "compileSdk" to 31,
        "targetSdk" to 31
    ))
  }
}

plugins {
  id("com.android.application") version "7.4.2" apply false
  id("com.android.library") version "7.4.2" apply false
  id("org.jetbrains.kotlin.android") version "1.6.21" apply false
  id("com.diffplug.spotless") version "6.17.0"
  id("com.vanniktech.maven.publish") version "0.25.3"
}

subprojects {
  apply(plugin = "com.diffplug.spotless")
  extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension>("spotless") {
    kotlin {
      target("src/**/*.kt")
      // ktlint doesn't honour .editorconfig yet: https://github.com/diffplug/spotless/issues/142
      ktlint("0.48.2").editorConfigOverride(mapOf(
          "insert_final_newline" to "true",
          "end_of_line" to "lf",
          "charset" to "utf-8",
          "indent_size" to "2"
      ))
      licenseHeaderFile(rootProject.file("gradle/license-header.txt"))
    }
    java {
      target("src/**/*.java")
    }
  }
}

val NEXT_VERSION = "2.3.1-SNAPSHOT"

mavenPublishing {
  val group = "app.cash.paykit"
  val version = "2.3.0-SNAPSHOT"

  coordinates(group, "", version)
  publishToMavenCentral(SonatypeHost.DEFAULT, true)
  signAllPublications()
  pom {
    description.set("Cash App Pay SDK")
    name.set(project.name)
    url.set("https://github.com/cashapp/android-cash-paykit-sdk/")
    licenses {
      license {
        name.set("The Apache Software License, Version 2.0")
        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
        distribution.set("repo")
      }
    }
    developers {
      developer {
        id.set("square")
        name.set("Square, Inc.")
      }
    }
    scm {
      url.set("https://github.com/cashapp/android-cash-paykit-sdk/")
      connection.set("scm:git:git://github.com/cashapp/android-cash-paykit-sdk.git")
      developerConnection.set("scm:git:ssh://git@github.com/cashapp/android-cash-paykit-sdk.git")
    }
  }
}