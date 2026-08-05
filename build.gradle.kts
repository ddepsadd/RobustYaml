import com.jetbrains.plugin.structure.base.utils.isFile
import groovy.ant.FileNameFinder
import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.intellij.platform.gradle.Constants
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    alias(libs.plugins.kotlinJvm)
    id("org.jetbrains.intellij.platform") version "2.18.1"     // See https://github.com/JetBrains/intellij-platform-gradle-plugin/releases
    id("me.filippov.gradle.jvm.wrapper") version "0.14.0"
}

jvmWrapper {
    linuxX64JvmUrl = "https://download.oracle.com/java/25/archive/jdk-25.0.3_linux-x64_bin.tar.gz"
    linuxAarch64JvmUrl = "https://download.oracle.com/java/25/archive/jdk-25.0.3_linux-aarch64_bin.tar.gz"
    macX64JvmUrl = "https://download.oracle.com/java/25/archive/jdk-25.0.3_macos-x64_bin.tar.gz"
    macAarch64JvmUrl = "https://download.oracle.com/java/25/archive/jdk-25.0.3_macos-aarch64_bin.tar.gz"
    windowsX64JvmUrl = "https://download.oracle.com/java/25/archive/jdk-25.0.3_windows-x64_bin.zip"
}

val isWindows = Os.isFamily(Os.FAMILY_WINDOWS)
extra["isWindows"] = isWindows

val DotnetSolution = providers.gradleProperty("DotnetSolution").get()
val BuildConfiguration = providers.gradleProperty("BuildConfiguration").get()
val ProductVersion = providers.gradleProperty("ProductVersion").get()
val DotnetPluginId = providers.gradleProperty("DotnetPluginId").get()
val RiderPluginId = providers.gradleProperty("RiderPluginId").get()
val PublishToken = providers.gradleProperty("PublishToken").get()

allprojects {
    repositories {
        maven { setUrl("https://cache-redirector.jetbrains.com/maven-central") }
    }
}

repositories {
    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
    }
}

tasks.wrapper {
    gradleVersion = "9.6.1"
    distributionType = Wrapper.DistributionType.ALL
    distributionUrl = "https://cache-redirector.jetbrains.com/services.gradle.org/distributions/gradle-${gradleVersion}-all.zip"
}

version = providers.gradleProperty("PluginVersion").get()

tasks.processResources {
    from("dependencies.json") { into("META-INF") }
}

sourceSets {
    main {
        java.srcDir("src/rider/main/java")
        kotlin.srcDir("src/rider/main/kotlin")
        resources.srcDir("src/rider/main/resources")
    }
}

tasks.compileKotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

// On Windows prefer the MSBuild shipped with Visual Studio, elsewhere fall back to `dotnet msbuild`.
val visualStudioPath: Provider<String> =
    if (isWindows) {
        providers.exec {
            commandLine("$rootDir\\tools\\vswhere.exe", "-latest", "-property", "installationPath", "-products", "*")
        }.standardOutput.asText.map { it.trim() }
    } else {
        providers.provider { "" }
    }

val msbuildExecutable: Provider<String> = visualStudioPath.map { path ->
    when {
        path.isEmpty() -> "dotnet"
        else -> FileNameFinder().getFileNames("$path\\MSBuild", "**/MSBuild.exe").first()
    }
}

val msbuildArgs: Provider<List<String>> = msbuildExecutable.map { executable ->
    val prefix = if (executable == "dotnet") listOf("msbuild") else listOf("/v:minimal")
    prefix + listOf(DotnetSolution, "/p:Configuration=$BuildConfiguration", "/p:HostFullIdentifier=")
}

val latestChangeNotes: String = Regex("(?s)(-.+?)(?=##|\$)")
    .findAll(file("$rootDir/CHANGELOG.md").readText())
    .mapNotNull { it.groups[1]?.value }
    .firstOrNull()
    .orEmpty()

val compileDotNet = tasks.register<Exec>("compileDotNet") {
    workingDir = rootDir
    executable(msbuildExecutable.get())
    args(msbuildArgs.get() + "/t:Restore;Rebuild")
}

val testDotNet = tasks.register<Exec>("testDotNet") {
    workingDir = rootDir
    executable("dotnet")
    args("test", DotnetSolution, "--logger", "GitHubActions")
}

val packDotNet = tasks.register<Exec>("packDotNet") {
    workingDir = rootDir
    executable(msbuildExecutable.get())
    args(
        msbuildArgs.get() + listOf(
            "/t:Pack",
            "/p:PackageOutputPath=$rootDir/output",
            "/p:PackageReleaseNotes=" + latestChangeNotes
                .replace(Regex("(?s)- "), "• ")
                .replace("`", "")
                .replace(",", "%2C")
                .replace(";", "%3B"),
            "/p:PackageVersion=$version",
        )
    )
}

val copyPluginZip = tasks.register<Copy>("copyPluginZip") {
    from(layout.buildDirectory.file("distributions/${rootProject.name}-${version}.zip"))
    into(layout.projectDirectory.dir("output"))
}

val pushNuGet = tasks.register<Exec>("pushNuGet") {
    workingDir = rootDir
    executable("dotnet")
    args(
        "nuget", "push", "output/${DotnetPluginId}.${version}.nupkg",
        "--api-key", PublishToken,
        "--source", "https://plugins.jetbrains.com",
    )
}

tasks.buildPlugin {
    finalizedBy(copyPluginZip, packDotNet)
}

dependencies {
    intellijPlatform {
        rider(ProductVersion) { useInstaller.set(false) }
        jetbrainsRuntime()
        bundledPlugin("org.jetbrains.plugins.yaml")
        bundledModule("intellij.rider.rdclient.dotnet")
        plugin("com.github.catppuccin.jetbrains:3.6.1")
        plugin("com.github.catppuccin.jetbrains_icons:1.13.2")
    }
}

tasks.runIde {
    // Match Rider's default heap size of 1.5Gb (default for runIde is 512Mb)
    maxHeapSize = "1500m"
}

tasks.patchPluginXml {
    changeNotes.set(latestChangeNotes.replace(Regex("(?s)\r?\n"), "<br />\n"))
}

tasks.prepareSandbox {
    dependsOn(compileDotNet)

    val outputFolder = "${rootDir}/src/dotnet/${DotnetPluginId}/bin/${DotnetPluginId}.Rider/${BuildConfiguration}"
    val dllFiles = listOf(
            "$outputFolder/${DotnetPluginId}.dll",
            "$outputFolder/${DotnetPluginId}.pdb",

            // TODO: add additional assemblies
    )

    dllFiles.forEach({ f ->
        val file = file(f)
        from(file, { into("${rootProject.name}/dotnet") })
    })

    doLast {
        dllFiles.forEach({ f ->
            val file = file(f)
            if (!file.exists()) throw RuntimeException("File ${file} does not exist")
        })
    }
}

tasks.publishPlugin {
    dependsOn(testDotNet)
    dependsOn(tasks.buildPlugin)
    token.set(PublishToken)
    finalizedBy(pushNuGet)
}

val riderModel = configurations.consumable("riderModel")

artifacts {
    add(riderModel.name, provider {
        intellijPlatform.platformPath.resolve("lib/rd/rider-model.jar").also {
            check(it.isFile) {
                "rider-model.jar is not found at $riderModel"
            }
        }
    }) {
        builtBy(Constants.Tasks.INITIALIZE_INTELLIJ_PLATFORM_PLUGIN)
    }
}
