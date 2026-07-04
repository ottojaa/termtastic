plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "web.js"
                cssSupport {
                    enabled.set(true)
                }
            }
            binaries.executable()
        }
    }

    sourceSets {
        jsMain.dependencies {
            implementation(projects.clientServer)
            implementation(projects.client)
            implementation(libs.darkness.web)
            implementation(libs.kotlinx.html)
            implementation(libs.kotlinx.coroutines.core)
            implementation(npm("xterm", "5.3.0"))
            implementation(npm("xterm-addon-fit", "0.8.0"))
            // three.js for the 3D tab overview (Overview3D.kt). Pinned to a
            // release whose package.json still maps `require("three")` to
            // build/three.cjs — the Kotlin/JS UMD externals compile to a
            // CommonJS require, so the CJS entry point must exist.
            implementation(npm("three", "0.170.0"))
        }
    }
}
