plugins {
    application
}

group = "org.opendamn"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("com.formdev:flatlaf:3.7.2")
    implementation("com.formdev:flatlaf-extras:3.7.2")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "org.opendamn.Main"
    applicationName = "opendamn"
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

// The About dialog reads Implementation-Version, so it must be set or it shows no version.
val manifestAttributes = mapOf(
    "Main-Class" to "org.opendamn.Main",
    "Implementation-Title" to "OpenDAMN",
    "Implementation-Version" to version,
    "Implementation-Vendor" to "OpenDAMN contributors",
)

tasks.jar {
    manifest { attributes(manifestAttributes) }
}

// Single runnable jar for users who just want to double-click.
val fatJar by tasks.registering(Jar::class) {
    archiveClassifier = "all"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes(manifestAttributes) }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
}

tasks.test {
    // Point at a folder of real plugins to run the read-only real-data checks.
    providers.environmentVariable("OPENDAMN_SAMPLES").orNull?.let { systemProperty("opendamn.samples", it) }
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
