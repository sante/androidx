/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.build

import androidx.benchmark.gradle.BenchmarkPlugin
import androidx.build.AndroidXPlugin.Companion.CHECK_RELEASE_READY_TASK
import androidx.build.AndroidXPlugin.Companion.TASK_TIMEOUT_MINUTES
import androidx.build.SupportConfig.BUILD_TOOLS_VERSION
import androidx.build.SupportConfig.COMPILE_SDK_VERSION
import androidx.build.SupportConfig.DEFAULT_MIN_SDK_VERSION
import androidx.build.SupportConfig.INSTRUMENTATION_RUNNER
import androidx.build.SupportConfig.TARGET_SDK_VERSION
import androidx.build.checkapi.JavaApiTaskConfig
import androidx.build.checkapi.KmpApiTaskConfig
import androidx.build.checkapi.LibraryApiTaskConfig
import androidx.build.checkapi.configureProjectForApiTasks
import androidx.build.dependencyTracker.AffectedModuleDetector
import androidx.build.gradle.getByType
import androidx.build.gradle.isRoot
import androidx.build.license.configureExternalDependencyLicenseCheck
import androidx.build.resources.configurePublicResourcesStub
import androidx.build.studio.StudioTask
import androidx.build.testConfiguration.addAppApkToTestConfigGeneration
import androidx.build.testConfiguration.addToTestZips
import androidx.build.testConfiguration.configureTestConfigGeneration
import com.android.build.api.extension.LibraryAndroidComponentsExtension
import com.android.build.gradle.AppExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.LibraryPlugin
import com.android.build.gradle.TestedExtension
import com.android.build.gradle.api.ApkVariant
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion.VERSION_1_8
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getPlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePluginWrapper
import org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A plugin which enables all of the Gradle customizations for AndroidX.
 * This plugin reacts to other plugins being added and adds required and optional functionality.
 */
class AndroidXPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project.isRoot) throw Exception("Root project should use AndroidXRootPlugin instead")
        val extension = project.extensions.create<AndroidXExtension>(EXTENSION_NAME, project)
        // Perform different actions based on which plugins have been applied to the project.
        // Many of the actions overlap, ex. API tracking.
        project.plugins.all { plugin ->
            when (plugin) {
                is JavaPlugin -> configureWithJavaPlugin(project, extension)
                is LibraryPlugin -> configureWithLibraryPlugin(project, extension)
                is AppPlugin -> configureWithAppPlugin(project, extension)
                is KotlinBasePluginWrapper -> configureWithKotlinPlugin(project, extension, plugin)
            }
        }

        project.configureKtlint()

        // Configure all Jar-packing tasks for hermetic builds.
        project.tasks.withType(Jar::class.java).configureEach { it.configureForHermeticBuild() }
        project.tasks.withType(Copy::class.java).configureEach { it.configureForHermeticBuild() }

        // copy host side test results to DIST
        project.tasks.withType(Test::class.java) { task -> configureTestTask(project, task) }

        project.configureTaskTimeouts()
        project.configureMavenArtifactUpload(extension)
        project.configureExternalDependencyLicenseCheck()
    }

    /**
     * Disables timestamps and ensures filesystem-independent archive ordering to maximize
     * cross-machine byte-for-byte reproducibility of artifacts.
     */
    private fun Jar.configureForHermeticBuild() {
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false
    }

    private fun Copy.configureForHermeticBuild() {
        duplicatesStrategy = DuplicatesStrategy.FAIL
    }

    private fun configureTestTask(project: Project, task: Test) {
        AffectedModuleDetector.configureTaskGuard(task)

        val xmlReportDestDir = project.getHostTestResultDirectory()
        val archiveName = "${project.path.asFilenamePrefix()}_${task.name}.zip"
        if (project.isDisplayTestOutput()) {
            // Enable tracing to see results in command line
            task.testLogging.apply {
                events = hashSetOf(
                    TestLogEvent.FAILED, TestLogEvent.PASSED,
                    TestLogEvent.SKIPPED, TestLogEvent.STANDARD_OUT
                )
                showExceptions = true
                showCauses = true
                showStackTraces = true
                exceptionFormat = TestExceptionFormat.FULL
            }
        } else {
            task.testLogging.apply {
                showExceptions = false
                // Disable all output, including the names of the failing tests, by specifying
                // that the minimum granularity we're interested in is this very high number
                // (which is higher than the current maximum granularity that Gradle offers (3))
                minGranularity = 1000
            }
            val htmlReport = task.reports.html

            val zipHtmlTask = project.tasks.register(
                "zipHtmlResultsOf${task.name.capitalize()}",
                Zip::class.java
            ) {
                val destinationDirectory = File("$xmlReportDestDir-html")
                it.destinationDirectory.set(destinationDirectory)
                it.archiveFileName.set(archiveName)
                it.doLast {
                    // If the test itself didn't display output, then the report task should
                    // remind the user where to find its output
                    project.logger.lifecycle(
                        "Html results of ${task.name} zipped into " +
                            "$destinationDirectory/$archiveName"
                    )
                }
            }
            task.finalizedBy(zipHtmlTask)
            task.doFirst {
                zipHtmlTask.configure {
                    it.from(htmlReport.destination)
                }
            }
            val xmlReport = task.reports.junitXml
            if (xmlReport.isEnabled) {
                val zipXmlTask = project.tasks.register(
                    "zipXmlResultsOf${task.name.capitalize()}",
                    Zip::class.java
                ) {
                    it.destinationDirectory.set(xmlReportDestDir)
                    it.archiveFileName.set(archiveName)
                }
                if (project.hasProperty(TEST_FAILURES_DO_NOT_FAIL_TEST_TASK)) {
                    task.ignoreFailures = true
                }
                task.finalizedBy(zipXmlTask)
                task.doFirst {
                    zipXmlTask.configure {
                        it.from(xmlReport.destination)
                    }
                }
            }
        }
        task.systemProperty("robolectric.offline", "true")
        val robolectricDependencies =
            File(project.getPrebuiltsRoot(), "androidx/external/org/robolectric/android-all")
        task.systemProperty(
            "robolectric.dependency.dir",
            robolectricDependencies.absolutePath
        )
    }

    private fun configureWithKotlinPlugin(
        project: Project,
        extension: AndroidXExtension,
        plugin: KotlinBasePluginWrapper
    ) {
        project.tasks.withType(KotlinCompile::class.java).configureEach { task ->
            task.kotlinOptions.jvmTarget = "1.8"
            project.configureJavaCompilationWarnings(task)
            if (project.hasProperty(EXPERIMENTAL_KOTLIN_BACKEND_ENABLED)) {
                task.kotlinOptions.freeCompilerArgs += listOf("-Xuse-ir=true")
            }

            // Not directly impacting us, but a bunch of issues like KT-46512, probably prudent
            // for us to just disable until Kotlin 1.5.10+ to avoid end users hitting users
            task.kotlinOptions.freeCompilerArgs += listOf("-Xsam-conversions=class")
        }
        project.afterEvaluate {
            if (extension.shouldEnforceKotlinStrictApiMode()) {
                project.tasks.withType(KotlinCompile::class.java).configureEach { task ->
                    // Workaround for https://youtrack.jetbrains.com/issue/KT-37652
                    if (task.name.endsWith("TestKotlin")) return@configureEach
                    task.kotlinOptions.freeCompilerArgs += listOf("-Xexplicit-api=strict")
                }
            }
        }
        if (plugin is KotlinMultiplatformPluginWrapper) {
            project.extensions.findByType<LibraryExtension>()?.apply {
                configureAndroidLibraryWithMultiplatformPluginOptions()
            }
        }
    }

    @Suppress("UnstableApiUsage") // AGP DSL APIs
    private fun configureWithAppPlugin(project: Project, androidXExtension: AndroidXExtension) {
        val appExtension = project.extensions.getByType<AppExtension>().apply {
            configureAndroidCommonOptions(project, androidXExtension)
            configureAndroidApplicationOptions(project)
        }

        // TODO: Replace this with a per-variant packagingOption for androidTest specifically once
        //  b/69953968 is resolved.
        appExtension.packagingOptions.resources {
            // Workaround for b/161465530 in AGP that fails to strip these <module>.kotlin_module files,
            // which causes mergeDebugAndroidTestJavaResource to fail for sample apps.
            excludes.add("/META-INF/*.kotlin_module")
            // Workaround a limitation in AGP that fails to merge these META-INF license files.
            pickFirsts.add("/META-INF/AL2.0")
            // In addition to working around the above issue, we exclude the LGPL2.1 license as we're
            // approved to distribute code via AL2.0 and the only dependencies which pull in LGPL2.1
            // are currently dual-licensed with AL2.0 and LGPL2.1. The affected dependencies are:
            //   - net.java.dev.jna:jna:5.5.0
            excludes.add("/META-INF/LGPL2.1")
        }
        project.configureAndroidProjectForLint(appExtension.lintOptions, androidXExtension)
    }

    @Suppress("UnstableApiUsage") // AGP DSL APIs
    private fun configureWithLibraryPlugin(
        project: Project,
        androidXExtension: AndroidXExtension
    ) {
        val libraryExtension = project.extensions.getByType<LibraryExtension>().apply {
            configureAndroidCommonOptions(project, androidXExtension)
            configureAndroidLibraryOptions(project, androidXExtension)
        }

        project.extensions.getByType<LibraryAndroidComponentsExtension>().apply {
            beforeVariants(selector().withBuildType("release")) { variant ->
                variant.enableUnitTest = false
            }
        }

        libraryExtension.packagingOptions.resources {
            // TODO: Replace this with a per-variant packagingOption for androidTest specifically
            //  once b/69953968 is resolved.
            // Workaround for b/161465530 in AGP that fails to merge these META-INF license files
            // for libraries that publish Java resources under the same name.
            pickFirsts.add("/META-INF/AL2.0")
            // In addition to working around the above issue, we exclude the LGPL2.1 license as we're
            // approved to distribute code via AL2.0 and the only dependencies which pull in LGPL2.1
            // currently are dual-licensed with AL2.0 and LGPL2.1. The affected dependencies are:
            //   - net.java.dev.jna:jna:5.5.0
            excludes.add("/META-INF/LGPL2.1")

            check(!excludes.contains("/META-INF/*.kotlin_module"))
        }

        project.configurePublicResourcesStub(libraryExtension)
        project.configureSourceJarForAndroid(libraryExtension)
        project.configureVersionFileWriter(libraryExtension, androidXExtension)
        project.addCreateLibraryBuildInfoFileTask(androidXExtension)
        project.configureJavaCompilationWarnings(androidXExtension)

        project.configureDependencyVerification(androidXExtension) { taskProvider ->
            libraryExtension.defaultPublishVariant { libraryVariant ->
                taskProvider.configure { task ->
                    task.dependsOn(libraryVariant.javaCompileProvider)
                }
            }
        }

        val reportLibraryMetrics = project.configureReportLibraryMetricsTask()
        project.addToBuildOnServer(reportLibraryMetrics)
        libraryExtension.defaultPublishVariant { libraryVariant ->
            reportLibraryMetrics.configure {
                it.jarFiles.from(
                    libraryVariant.packageLibraryProvider.map { zip ->
                        zip.inputs.files
                    }
                )
            }
        }

        // Standard lint, docs, resource API, and Metalava configuration for AndroidX projects.
        project.configureAndroidProjectForLint(libraryExtension.lintOptions, androidXExtension)
        project.configureProjectForApiTasks(
            LibraryApiTaskConfig(libraryExtension),
            androidXExtension
        )

        project.addToProjectMap(androidXExtension)
    }

    private fun configureWithJavaPlugin(project: Project, extension: AndroidXExtension) {
        project.configureErrorProneForJava()
        project.configureSourceJarForJava()

        // Force Java 1.8 source- and target-compatibilty for all Java libraries.
        val convention = project.convention.getPlugin<JavaPluginConvention>()
        convention.apply {
            sourceCompatibility = VERSION_1_8
            targetCompatibility = VERSION_1_8
        }

        project.configureJavaCompilationWarnings(extension)

        project.hideJavadocTask()

        project.configureDependencyVerification(extension) { taskProvider ->
            taskProvider.configure { task ->
                task.dependsOn(project.tasks.named(JavaPlugin.COMPILE_JAVA_TASK_NAME))
            }
        }

        project.addCreateLibraryBuildInfoFileTask(extension)

        // Standard lint, docs, and Metalava configuration for AndroidX projects.
        project.configureNonAndroidProjectForLint(extension)
        val apiTaskConfig = if (project.multiplatformExtension != null) {
            KmpApiTaskConfig
        } else {
            JavaApiTaskConfig
        }
        project.configureProjectForApiTasks(apiTaskConfig, extension)

        project.afterEvaluate {
            if (extension.type.publish.shouldRelease()) {
                project.extra.set("publish", true)
            }
        }

        // Workaround for b/120487939 wherein Gradle's default resolution strategy prefers external
        // modules with lower versions over local projects with higher versions.
        project.configurations.all { configuration ->
            configuration.resolutionStrategy.preferProjectModules()
        }

        project.addToProjectMap(extension)
    }

    private fun TestedExtension.configureAndroidCommonOptions(
        project: Project,
        androidXExtension: AndroidXExtension
    ) {
        compileOptions.apply {
            sourceCompatibility = VERSION_1_8
            targetCompatibility = VERSION_1_8
        }

        compileSdkVersion(COMPILE_SDK_VERSION)
        buildToolsVersion = BUILD_TOOLS_VERSION
        defaultConfig.targetSdk = TARGET_SDK_VERSION
        ndkVersion = SupportConfig.NDK_VERSION
        ndkPath = project.getNdkPath().absolutePath

        defaultConfig.testInstrumentationRunner = INSTRUMENTATION_RUNNER

        testOptions.animationsDisabled = true
        testOptions.unitTests.isReturnDefaultValues = true

        // Include resources in Robolectric tests as a workaround for b/184641296 and
        // ensure the build directory exists as a workaround for b/187970292.
        testOptions.unitTests.isIncludeAndroidResources = true
        if (!project.buildDir.exists()) project.buildDir.mkdirs()

        defaultConfig.minSdk = DEFAULT_MIN_SDK_VERSION
        project.afterEvaluate {
            val minSdkVersion = defaultConfig.minSdk!!
            check(minSdkVersion >= DEFAULT_MIN_SDK_VERSION) {
                "minSdkVersion $minSdkVersion lower than the default of $DEFAULT_MIN_SDK_VERSION"
            }
            project.configurations.all { configuration ->
                configuration.resolutionStrategy.eachDependency { dep ->
                    val target = dep.target
                    val version = target.version
                    // Enforce the ban on declaring dependencies with version ranges.
                    // Note: In playground, this ban is exempted to allow unresolvable prebuilts
                    // to automatically get bumped to snapshot versions via version range
                    // substitution.
                    if (version != null && Version.isDependencyRange(version) &&
                        project.rootProject.rootDir == project.getSupportRootFolder()
                    ) {
                        throw IllegalArgumentException(
                            "Dependency ${dep.target} declares its version as " +
                                "version range ${dep.target.version} however the use of " +
                                "version ranges is not allowed, please update the " +
                                "dependency to list a fixed version."
                        )
                    }
                }
            }

            if (androidXExtension.type.compilationTarget != CompilationTarget.DEVICE) {
                throw IllegalStateException(
                    "${androidXExtension.type.name} libraries cannot apply the android plugin, as" +
                        " they do not target android devices"
                )
            }
        }

        val debugSigningConfig = signingConfigs.getByName("debug")
        // Use a local debug keystore to avoid build server issues.
        debugSigningConfig.storeFile = project.getKeystore()
        buildTypes.all { buildType ->
            // Sign all the builds (including release) with debug key
            buildType.signingConfig = debugSigningConfig
        }

        project.configureErrorProneForAndroid(variants)

        // workaround for b/120487939
        project.configurations.all { configuration ->
            // Gradle seems to crash on androidtest configurations
            // preferring project modules...
            if (!configuration.name.toLowerCase(Locale.US).contains("androidtest")) {
                configuration.resolutionStrategy.preferProjectModules()
            }
        }

        project.configureTestConfigGeneration(this)

        val buildTestApksTask = project.rootProject.tasks.named(BUILD_TEST_APKS_TASK)
        testVariants.all { variant ->
            buildTestApksTask.configure {
                it.dependsOn(variant.assembleProvider)
            }
            variant.configureApkCopy(project, true)
        }

        // AGP warns if we use project.buildDir (or subdirs) for CMake's generated
        // build files (ninja build files, CMakeCache.txt, etc.). Use a staging directory that
        // lives alongside the project's buildDir.
        externalNativeBuild.cmake.buildStagingDirectory =
            File(project.buildDir, "../nativeBuildStaging")
    }

    private fun ApkVariant.configureApkCopy(
        project: Project,
        testApk: Boolean
    ) {
        packageApplicationProvider.get().let { packageTask ->
            AffectedModuleDetector.configureTaskGuard(packageTask)
            // Skip copying AndroidTest apks if they have no source code (no tests to run).
            if (testApk && !project.hasAndroidTestSourceCode()) {
                return
            }

            addToTestZips(project, packageTask)

            packageTask.doLast {
                project.copy {
                    it.from(packageTask.outputDirectory)
                    it.include("*.apk")
                    it.into(File(project.getDistributionDirectory(), "apks"))
                    it.rename { fileName ->
                        fileName.renameApkForTesting(project.path, project.hasBenchmarkPlugin())
                    }
                }
            }
        }
    }

    private fun LibraryExtension.configureAndroidLibraryOptions(
        project: Project,
        androidXExtension: AndroidXExtension
    ) {
        project.configurations.all { config ->
            val isTestConfig = config.name.toLowerCase(Locale.US).contains("test")

            config.dependencyConstraints.configureEach { dependencyConstraint ->
                dependencyConstraint.apply {
                    // Remove strict constraints on test dependencies and listenablefuture:1.0
                    if (isTestConfig ||
                        group == "com.google.guava" &&
                        name == "listenablefuture" &&
                        version == "1.0"
                    ) {
                        version { versionConstraint ->
                            versionConstraint.strictly("")
                        }
                    }
                }
            }
        }

        project.afterEvaluate {
            if (androidXExtension.publish.shouldRelease()) {
                project.extra.set("publish", true)
            }
        }
    }

    private fun TestedExtension.configureAndroidLibraryWithMultiplatformPluginOptions() {
        sourceSets.findByName("main")!!.manifest.srcFile("src/androidMain/AndroidManifest.xml")
        sourceSets.findByName("androidTest")!!
            .manifest.srcFile("src/androidAndroidTest/AndroidManifest.xml")
    }

    private fun AppExtension.configureAndroidApplicationOptions(project: Project) {
        defaultConfig.apply {
            versionCode = 1
            versionName = "1.0"
        }

        lintOptions.apply {
            isAbortOnError = true

            val baseline = project.lintBaseline
            if (baseline.exists()) {
                baseline(baseline)
            }
        }

        project.addAppApkToTestConfigGeneration()

        val buildTestApksTask = project.rootProject.tasks.named(BUILD_TEST_APKS_TASK)
        applicationVariants.all { variant ->
            // Using getName() instead of name due to b/150427408
            if (variant.buildType.name == "debug") {
                buildTestApksTask.configure {
                    it.dependsOn(variant.assembleProvider)
                }
            }
            variant.configureApkCopy(project, false)
        }
    }

    private fun Project.configureDependencyVerification(
        extension: AndroidXExtension,
        taskConfigurator: (TaskProvider<VerifyDependencyVersionsTask>) -> Unit
    ) {
        afterEvaluate {
            if (extension.type != LibraryType.SAMPLES) {
                val verifyDependencyVersionsTask = project.createVerifyDependencyVersionsTask()
                if (verifyDependencyVersionsTask != null) {
                    project.createCheckReleaseReadyTask(listOf(verifyDependencyVersionsTask))
                    taskConfigurator(verifyDependencyVersionsTask)
                }
            }
        }
    }

    private fun Project.createVerifyDependencyVersionsTask():
        TaskProvider<VerifyDependencyVersionsTask>? {
            /**
             * Ignore -Pandroidx.useMaxDepVersions when verifying dependency versions because it is a
             * hypothetical build which is only intended to check for forward compatibility.
             */
            if (project.usingMaxDepVersions()) {
                return null
            }

            val taskProvider = tasks.register(
                "verifyDependencyVersions",
                VerifyDependencyVersionsTask::class.java
            )
            addToBuildOnServer(taskProvider)
            return taskProvider
        }

    // Task that creates a json file of a project's dependencies
    private fun Project.addCreateLibraryBuildInfoFileTask(extension: AndroidXExtension) {
        afterEvaluate {
            if (extension.publish.shouldRelease()) {
                // Only generate build info files for published libraries.
                val task = tasks.register(
                    CREATE_LIBRARY_BUILD_INFO_FILES_TASK,
                    CreateLibraryBuildInfoFileTask::class.java
                ) {
                    it.outputFile.set(
                        File(
                            project.getBuildInfoDirectory(),
                            "${group}_${name}_build_info.txt"
                        )
                    )
                }
                rootProject.tasks.named(CREATE_LIBRARY_BUILD_INFO_FILES_TASK).configure {
                    it.dependsOn(task)
                }
                addTaskToAggregateBuildInfoFileTask(task)
            }
        }
    }

    private fun Project.addTaskToAggregateBuildInfoFileTask(
        task: TaskProvider<CreateLibraryBuildInfoFileTask>
    ) {
        rootProject.tasks.named(CREATE_AGGREGATE_BUILD_INFO_FILES_TASK).configure {
            val aggregateLibraryBuildInfoFileTask: CreateAggregateLibraryBuildInfoFileTask = it
                as CreateAggregateLibraryBuildInfoFileTask
            aggregateLibraryBuildInfoFileTask.dependsOn(task)
            aggregateLibraryBuildInfoFileTask.libraryBuildInfoFiles.add(
                task.flatMap { task -> task.outputFile }
            )
        }
    }

    companion object {
        const val BUILD_ON_SERVER_TASK = "buildOnServer"
        const val BUILD_TEST_APKS_TASK = "buildTestApks"
        const val CHECK_RELEASE_READY_TASK = "checkReleaseReady"
        const val CREATE_LIBRARY_BUILD_INFO_FILES_TASK = "createLibraryBuildInfoFiles"
        const val CREATE_AGGREGATE_BUILD_INFO_FILES_TASK = "createAggregateBuildInfoFiles"
        const val GENERATE_TEST_CONFIGURATION_TASK = "GenerateTestConfiguration"
        const val REPORT_LIBRARY_METRICS_TASK = "reportLibraryMetrics"
        const val ZIP_TEST_CONFIGS_WITH_APKS_TASK = "zipTestConfigsWithApks"
        const val ZIP_CONSTRAINED_TEST_CONFIGS_WITH_APKS_TASK = "zipConstrainedTestConfigsWithApks"

        const val TASK_GROUP_API = "API"

        const val EXTENSION_NAME = "androidx"

        /**
         * Fail the build if a non-Studio task runs longer than expected
         */
        const val TASK_TIMEOUT_MINUTES = 45L
    }
}

private const val PROJECTS_MAP_KEY = "projects"
private const val ACCESSED_PROJECTS_MAP_KEY = "accessedProjectsMap"

/**
 * Hides a project's Javadoc tasks from the output of `./gradlew tasks` by setting their group to
 * `null`.
 *
 * AndroidX projects do not use the Javadoc task for docs generation, so we don't want them
 * cluttering up the task overview.
 */
private fun Project.hideJavadocTask() {
    tasks.withType(Javadoc::class.java).configureEach {
        if (it.name == "javadoc") {
            it.group = null
        }
    }
}

private fun Project.addToProjectMap(extension: AndroidXExtension) {
    // TODO(alanv): Move this out of afterEvaluate
    afterEvaluate {
        if (extension.publish.shouldRelease()) {
            val group = extension.mavenGroup?.group
            if (group != null) {
                val module = "$group:$name"

                if (project.rootProject.extra.has(ACCESSED_PROJECTS_MAP_KEY)) {
                    throw GradleException(
                        "Attempted to add $project to project map after " +
                            "the contents of the map were accessed"
                    )
                }
                @Suppress("UNCHECKED_CAST")
                val projectModules = project.rootProject.extra.get(PROJECTS_MAP_KEY)
                    as ConcurrentHashMap<String, String>
                projectModules[module] = path
            }
        }
    }
}

val Project.multiplatformExtension
    get() = extensions.findByType(KotlinMultiplatformExtension::class.java)

/**
 * Creates the [CHECK_RELEASE_READY_TASK], which aggregates tasks that must pass for a
 * project to be considered ready for public release.
 */
private fun Project.createCheckReleaseReadyTask(taskProviderList: List<TaskProvider<out Task>>) {
    tasks.register(CHECK_RELEASE_READY_TASK) {
        for (taskProvider in taskProviderList) {
            it.dependsOn(taskProvider)
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun Project.getProjectsMap(): ConcurrentHashMap<String, String> {
    project.rootProject.extra.set(ACCESSED_PROJECTS_MAP_KEY, true)
    return rootProject.extra.get(PROJECTS_MAP_KEY) as ConcurrentHashMap<String, String>
}

/**
 * Configures all non-Studio tasks in a project (see b/153193718 for background) to time out after
 * [TASK_TIMEOUT_MINUTES].
 */
private fun Project.configureTaskTimeouts() {
    tasks.configureEach { t ->
        // skip adding a timeout for some tasks that both take a long time and
        // that we can count on the user to monitor
        if (t !is StudioTask) {
            t.timeout.set(Duration.ofMinutes(TASK_TIMEOUT_MINUTES))
        }
    }
}

private fun Project.configureJavaCompilationWarnings(androidXExtension: AndroidXExtension) {
    afterEvaluate {
        project.tasks.withType(JavaCompile::class.java).configureEach { task ->
            if (hasProperty(ALL_WARNINGS_AS_ERRORS)) {
                // If we're running a hypothetical test build confirming that tip-of-tree versions
                // are compatible, then we're not concerned about warnings
                if (!project.usingMaxDepVersions()) {
                    task.options.compilerArgs.add("-Werror")
                    task.options.compilerArgs.add("-Xlint:unchecked")
                    if (androidXExtension.failOnDeprecationWarnings) {
                        task.options.compilerArgs.add("-Xlint:deprecation")
                    }
                }
            }
        }
    }
}

private fun Project.configureJavaCompilationWarnings(task: KotlinCompile) {
    if (hasProperty(ALL_WARNINGS_AS_ERRORS) &&
        !project.usingMaxDepVersions()
    ) {
        task.kotlinOptions.allWarningsAsErrors = true
    }
    task.kotlinOptions.freeCompilerArgs += listOf(
        "-Xskip-runtime-version-check",
        "-Xskip-metadata-version-check"
    )
}

/**
 * Guarantees unique names for the APKs, and modifies some of the suffixes. The APK name is used
 * to determine what gets run by our test runner
 */
fun String.renameApkForTesting(projectPath: String, hasBenchmarkPlugin: Boolean): String {
    val name =
        if (projectPath.contains("media") && projectPath.contains("version-compat-tests")) {
            // Exclude media*:version-compat-tests modules from
            // existing support library presubmit tests.
            this.replace("-debug-androidTest", "")
        } else if (hasBenchmarkPlugin) {
            this.replace("-androidTest", "-androidBenchmark")
        } else if (projectPath.endsWith("macrobenchmark")) {
            this.replace("-androidTest", "-androidMacrobenchmark")
        } else {
            this
        }
    return "${projectPath.asFilenamePrefix()}_$name"
}

fun Project.hasBenchmarkPlugin(): Boolean {
    return this.plugins.hasPlugin(BenchmarkPlugin::class.java)
}

/**
 * Returns a string that is a valid filename and loosely based on the project name
 * The value returned for each project will be distinct
 */
fun String.asFilenamePrefix(): String {
    return this.substring(1).replace(':', '-')
}

/**
 * Sets the specified [task] as a dependency of the top-level `check` task, ensuring that it runs
 * as part of `./gradlew check`.
 */
fun <T : Task> Project.addToCheckTask(task: TaskProvider<T>) {
    project.tasks.named("check").configure {
        it.dependsOn(task)
    }
}

/**
 * Expected to be called in afterEvaluate when all extensions are available
 */
internal fun Project.hasAndroidTestSourceCode(): Boolean {
    // check Java androidTest source set
    this.extensions.findByType(TestedExtension::class.java)!!.sourceSets
        .findByName("androidTest")?.let { sourceSet ->
            // using getSourceFiles() instead of sourceFiles due to b/150800094
            if (!sourceSet.java.getSourceFiles().isEmpty) return true
        }

    // check kotlin-android androidTest source set
    this.extensions.findByType(KotlinAndroidProjectExtension::class.java)
        ?.sourceSets?.findByName("androidTest")?.let {
            if (it.kotlin.files.isNotEmpty()) return true
        }

    // check kotlin-multiplatform androidAndroidTest source set
    this.multiplatformExtension?.apply {
        sourceSets.findByName("androidAndroidTest")?.let {
            if (it.kotlin.files.isNotEmpty()) return true
        }
    }

    return false
}
