plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "com.github.agylauncher"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        webstorm("2024.3")
        bundledPlugin("org.jetbrains.plugins.terminal")
        javaCompiler("243.21565.193")
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
        version = "1.0.1"
        description = """
            <p><strong>Unofficial launcher for Antigravity CLI (<code>agy</code>).</strong> Launch <code>agy</code> inside JetBrains IDE's built-in Terminal and send file, line-range, or diagnostic references directly from context menus. Supports English and Simplified Chinese (<code>zh_CN</code>).</p>
            <h3>Features</h3>
            <ul>
              <li><strong>One-click / Shortcut Launch</strong>: Open or focus the Antigravity Terminal tab via the toolbar icon, <em>Tools &rarr; Agy Launcher</em>, or shortcut (<code>Alt + Shift + A</code> on Windows/Linux, <code>Option + Shift + A</code> on macOS).</li>
              <li><strong>Send File &amp; Line References</strong>: Right-click files in Project View, Editor, or Editor Tabs (or press <code>Alt + Shift + S</code> on Windows/Linux, <code>Option + Shift + S</code> on macOS) to send the file path and selected line range (e.g. <code>#L10-L25</code>) to the active Antigravity terminal.</li>
              <li><strong>Send Diagnostic / Error</strong>: Right-click on a line with errors or warnings in the Editor (or press <code>Alt + Shift + D</code> on Windows/Linux, <code>Option + Shift + D</code> on macOS) to send the file path, line number, and diagnostic message (e.g. <code>src/api/user.ts#L42 [TS2322: ...]</code>).</li>
              <li><strong>Status Bar Context, Cache, Speed &amp; Quota Monitor</strong>: Passively monitors the <code>Antigravity</code> terminal and <code>statusLine</code> telemetry in two stacked rows: the top row shows context usage, cache hit rate, and output speed (<code>Ctx --% | Cache --% | Speed -- tok/s</code>), and the second row shows the 5h and weekly remaining quota (<code>5h Quota --% | Weekly --%</code>). Features <code>⚠️</code> alerts for high context (<code>&gt;= 80%</code>) or low quota (<code>&lt;= 20%</code>), hover tooltip with all quota pools and reset countdowns, left-click <code>/context</code> refresh with auto-close, and right-click actions menu (<code>/context</code> &amp; <code>/compact</code>).</li>
              <li><strong>Customizable Settings</strong>: Configure the <code>agy</code> executable path, default CLI arguments, terminal tab title, <code>@</code> prefix, relative paths, auto-launch, and status bar monitor options under <em>Settings &rarr; Tools &rarr; Agy Launcher</em>.</li>
              <li><strong>Bilingual UI</strong>: Automatically follows the IDE language (English / Simplified Chinese).</li>
            </ul>
            <h3>Shortcuts</h3>
            <table>
              <thead>
                <tr>
                  <th>Action</th>
                  <th>Windows / Linux</th>
                  <th>macOS</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>Launch / Focus Antigravity CLI</td>
                  <td><code>Alt + Shift + A</code></td>
                  <td><code>Option + Shift + A</code></td>
                </tr>
                <tr>
                  <td>Send File Reference to Antigravity</td>
                  <td><code>Alt + Shift + S</code></td>
                  <td><code>Option + Shift + S</code></td>
                </tr>
                <tr>
                  <td>Send Diagnostic / Error to Antigravity</td>
                  <td><code>Alt + Shift + D</code></td>
                  <td><code>Option + Shift + D</code></td>
                </tr>
              </tbody>
            </table>
            <p>All shortcuts can be customized in <em>Settings &rarr; Keymap &rarr; Plugins &rarr; Agy Launcher</em>.</p>
        """.trimIndent()
        changeNotes = """
            <h3>1.0.1</h3>
            <ul>
              <li>Show the status bar metrics widget only after launching Antigravity via the plugin, and automatically hide it &amp; stop polling when the terminal tab is closed.</li>
              <li>Fixed Orchestrator mode quota matching so the status bar displays Gemini 5h and weekly remaining quotas instead of falling back to third-party (<code>3p</code>) buckets.</li>
            </ul>
            <h3>1.0.0</h3>
            <ul>
              <li>Initial release: launch/focus Antigravity CLI in built-in Terminal, send file/line references, send editor diagnostics/errors, and monitor context usage, cache hit rate, output speed &amp; remaining quota in the status bar.</li>
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
