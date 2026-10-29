// Root project — plugin management + cross-cutting tasks.
// Per-module configuration lives in core/build.gradle.kts, the one module.

plugins {
    // Apply plugins at root with `apply false` so they're available to subprojects
    // but not configured here. Each module decides which to use.
    alias(libs.plugins.kotlin.multiplatform) apply false
    // The KMP-first Android library plugin — declared here because AGP is
    // already on the root buildscript classpath, so a versioned request in a
    // module cannot be version-checked and Gradle refuses it.
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.ktlint) // applied to root + subprojects through `subprojects {}` below
    alias(libs.plugins.spotless) // license header enforcement
    // The public ABI, committed: `apiCheck` fails when a signature changes
    // without `apiDump` being re-run and reviewed.
    alias(libs.plugins.binary.compatibility.validator)
}

// The JS tests run on the machine's own Node.js. A downloaded distribution
// would also work — its repository can be declared in settings — but the
// host's Node is one less moving part, and contributors running the JS
// tests have one. Everything else builds without it.
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    extensions.configure<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec> {
        download.set(false)
    }
}
allprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
        extensions.configure<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec> {
            download.set(false)
        }
    }
}

// All subprojects use the same group / version.
// The Maven group is the GitHub-verified namespace; the code's package root
// stays `io.pdfkt` — the same arrangement docxkt publishes under.
allprojects {
    group = "io.github.vlaushkin"
    version = "0.0.5"
}

// Apply ktlint + spotless to all subprojects with Kotlin sources
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.1") // ktlint binary version (not plugin version)
        verbose.set(true)
        outputToConsole.set(true)
        ignoreFailures.set(false)
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        }
        filter {
            exclude { it.file.path.contains("/generated/") }
            exclude { it.file.path.contains("/build/") }
        }
    }

    // Spotless enforces the license header on every source file
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            targetExclude("**/generated/**", "**/build/**")
            licenseHeaderFile(rootProject.file("HEADER.kt"))
        }
        kotlinGradle {
            target("*.gradle.kts")
            licenseHeaderFile(
                rootProject.file("HEADER.kt"),
                "(plugins|pluginManagement|import|@file:|//)",
            )
        }
    }
}
