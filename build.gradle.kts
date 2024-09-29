import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("org.jetbrains.kotlin.jvm") version "1.3.72"
  application
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
  implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.7")

  implementation("com.xenomachina:kotlin-argparser:2.0.7")
  listOf("core", "cio", "json", "jackson").forEach {
    implementation("io.ktor:ktor-client-$it:1.4.0")
  }

  "2.10.0".let { v ->
    implementation("com.fasterxml.jackson.core:jackson-databind:$v")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$v")
  }
}

application {
  // Define the main class for the application.
  mainClassName = "net.davidglasser.recurdo.AppKt"
}

tasks.withType<KotlinCompile>().all {
  kotlinOptions {
    freeCompilerArgs = freeCompilerArgs + listOf("-Xopt-in=kotlin.RequiresOptIn", "-XXLanguage:+NewInference")
  }
}

tasks.named("build") {
  // installDist sets up build/install for running directly
  dependsOn += "installDist"
}
