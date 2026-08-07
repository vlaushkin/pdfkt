/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
// :core — the whole library: the layered engine (file syntax, content streams,
// fonts, layout) and the `document { }` DSL on top. Two packages, one artifact:
// `io.pdfkt.api` is what a consumer writes against, `io.pdfkt.core.*` the model
// tier underneath.
//
// Targets: jvm() + Android + Apple + js/wasmJs. The web is first-class, which
// is what keeps compression and codecs pure common Kotlin: browser APIs are
// async and cannot back a sync `expect`.
//
// Android arrives last and arrives empty: androidMain carries no source,
// because the zero-platform-code principle held through deflate, inflate and
// PNG. There is no jvmAndAndroid intermediate source set either — nothing
// differs between the two to share. The target exists to produce an AAR of the
// same commonMain engine.
//
// The Android SDK is a CONFIGURATION-time requirement of the Android library
// plugin: without a local SDK even unrelated Gradle tasks refuse to configure.

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.dokka)
    `maven-publish`
    signing
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    jvm()

    // minSdk 23: the library itself touches no Android API — it is commonMain
    // Kotlin over ByteArray — so the floor is chosen for the consumer, not for
    // us, and set low because nothing here needs a newer ART.
    androidLibrary {
        namespace = "io.pdfkt.core"
        compileSdk = 36
        minSdk = 23
        // Pinned so the build tools match compileSdk instead of AGP's older
        // default (35.0.0), which would be downloaded alongside them.
        buildToolsVersion = "36.0.0"

        // The smoke that says the AAR is a PDF writer and not just a zip that
        // compiled: `androidHostTest` runs on the JDK, against the Android
        // variant of this module, with no device and no Robolectric. It is the
        // only test compilation of the Android target — an instrumented one
        // would need an emulator, and there is nothing Android-specific left
        // to instrument: androidMain is empty. The whole of commonTest rides
        // along on it, which is the closest thing there is to a statement that
        // the AAR behaves like the jar.
        withHostTest { }
    }

    // The Apple targets also assemble into one XCFramework — the binary the
    // Swift package's binaryTarget points at:
    // ./gradlew :core:assemblePdfktReleaseXCFramework
    val xcf = XCFramework("Pdfkt")
    listOf(iosX64(), iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Pdfkt"
            xcf.add(this)
        }
    }

    // IR is the only Kotlin/JS backend; both environments are declared so a
    // consumer can bundle for the browser or run under Node.
    js {
        browser {
            testTask {
                useKarma {
                    // The browser suite runs in the machine's own Chrome:
                    // ./gradlew :core:jsBrowserTest
                    useChromeHeadless()
                }
            }
        }
        nodejs()
        // A consumable JS library (package.json + .d.ts), so the facade in
        // jsMain reaches plain-JavaScript callers:
        // ./gradlew :core:jsNodeProductionLibraryDistribution
        binaries.library()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // The only non-stdlib runtime dependency: Sink is the public
            // output type the whole writer stack is built on.
            api(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
            // Reflection over the sealed hierarchy — PdfObjectHierarchyTest
            // asks the hierarchy which variants exist. Test-only.
            implementation(kotlin("reflect"))
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

dependencies {
    "jvmTestImplementation"(platform(libs.junit.bom))
    "androidHostTestImplementation"(platform(libs.junit.bom))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// The AAR, under its published name.
//
// `com.android.kotlin.multiplatform.library` builds ONE Android variant: there
// are no build types in its DSL (no `buildTypes { }`, no `debug`/`release`
// split), so it registers `assemble` and no `assembleRelease`. The single
// `build/outputs/aar/<module>.aar` it produces IS the artifact that gets
// published — the one this module's `android` Maven publication carries.
//
// So this is an alias, not a second variant: a name that says which artifact a
// release takes, and prints where it landed.
val aar: Provider<RegularFile> = layout.buildDirectory.file("outputs/aar/$name.aar")

tasks.register("assembleRelease") {
    group = "build"
    description = "Assembles the Android AAR of this module (the one variant the KMP library plugin builds)."
    dependsOn("assemble")
    doLast {
        logger.lifecycle("AAR: {}", aar.get().asFile.absolutePath)
    }
}

// The Adobe AFM licence must accompany the embedded metrics into binary
// artifacts too — NOTICE rides in every jar's META-INF.
tasks.withType<Jar>().configureEach {
    from(rootProject.file("NOTICE")) { into("META-INF") }
}

// Maven Central requires a -javadoc.jar per artifact; Dokka's HTML is what
// there is to put in it, and one jar attached to every publication is the
// KMP arrangement Central accepts.
val javadocJar = tasks.register<Jar>("javadocJar") {
    dependsOn(tasks.dokkaGeneratePublicationHtml)
    from(tasks.dokkaGeneratePublicationHtml.flatMap { it.outputDirectory })
    archiveClassifier.set("javadoc")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = when (name) {
            "kotlinMultiplatform" -> "pdfkt-core"
            else -> "pdfkt-core-$name"
        }
        artifact(javadocJar)
        pom {
            name.set("pdfkt-core")
            description.set(
                "PDF generation for Kotlin Multiplatform, written against the published " +
                    "specifications (ISO 32000-2, OpenType, Adobe AFM)",
            )
            url.set("https://github.com/vlaushkin/pdfkt")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("vlaushkin")
                    name.set("Vasily Laushkin")
                    email.set("vaslinux@gmail.com")
                }
            }
            scm {
                connection.set("scm:git:git://github.com/vlaushkin/pdfkt.git")
                developerConnection.set("scm:git:ssh://github.com/vlaushkin/pdfkt.git")
                url.set("https://github.com/vlaushkin/pdfkt")
            }
        }
    }
}

// Signing is required by Maven Central and absent everywhere else: with no
// signatory configured (signing.keyId etc. in the user's gradle.properties)
// the sign tasks skip, so local and CI builds do not need keys.
signing {
    isRequired = false
    sign(publishing.publications)
}

// Gradle refuses a publish task that consumes a sign task's output without
// ordering; publishing after signing is that ordering.
tasks.withType<AbstractPublishToMaven>().configureEach {
    mustRunAfter(tasks.withType<Sign>())
}
