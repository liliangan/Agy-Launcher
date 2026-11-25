plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "com.github.agylauncher"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        webstorm("2026.2")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.github.liliangan.agylauncher"
        name = "Agy Launcher"
        version = "1.0.0"
        description = """
            <p><strong>Unofficial launcher for Antigravity CLI (<code>agy</code>).</strong> Launch <code>agy</code> inside the built-in IDE Terminal and send file, line-range, or diagnostic references directly from context menus.</p>
            <h3>Features</h3>
            <ul>
              <li><strong>One-click / Shortcut Launch</strong>: Open or focus the Antigravity Terminal tab via toolbar button, <em>Tools &rarr; Agy Launcher</em> menu, or shortcut (<code>Alt+Shift+A</code> on Windows/Linux, <code>Option+Shift+A</code> on macOS).</li>
              <li><strong>Send File &amp; Line References</strong>: Right-click files in Project View, Editor, or Editor Tabs (or press <code>Alt+Shift+S</code> / <code>Option+Shift+S</code> on macOS) to inject path and selected line range (e.g. <code>#L10-L25</code>) into the active Antigravity terminal.</li>
              <li><strong>Send Diagnostic / Error</strong>: Right-click on an error or warning line in the Editor (or press <code>Alt+Shift+D</code> / <code>Option+Shift+D</code> on macOS) to send file path, line number, and diagnostic details directly to the terminal.</li>
              <li><strong>Status Bar Monitor</strong>: Passively track context usage (with &ge;80% <code>⚠️</code> warning) and streaming output speed (<code>tok/s</code>) from the Antigravity terminal in the status bar, with left-click <code>/context</code> auto-refresh and right-click <code>/compact</code> compression.</li>
              <li><strong>Customizable Settings</strong>: Configure executable path, default CLI flags, terminal tab title, <code>@</code> prefix, relative paths, auto-launch, and status bar monitor behavior under <em>Settings &rarr; Tools &rarr; Agy Launcher</em>.</li>
              <li><strong>Bilingual UI</strong>: Supports both English and Simplified Chinese (zh_CN).</li>
            </ul>
        """.trimIndent()
        changeNotes = """
            <h3>1.0.0</h3>
            <ul>
              <li>Initial release: launch/focus Antigravity CLI in built-in Terminal, send file/line references, send editor diagnostics/errors, and monitor cache hit rate &amp; output speed in the status bar.</li>
              <li>Added toolbar, Tools menu, and context menu integrations with light/dark theme icons.</li>
              <li>Added English and Simplified Chinese localization.</li>
            </ul>
        """.trimIndent()
        vendor {
            name = providers.gradleProperty("pluginVendorName").orElse("Agy Launcher Contributors")
            email = providers.gradleProperty("pluginVendorEmail")
            url = providers.gradleProperty("pluginVendorUrl")
        }
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}
