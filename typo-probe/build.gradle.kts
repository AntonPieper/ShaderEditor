import javax.inject.Inject

plugins {
	alias(libs.plugins.android.application)
}

// The typo experiment's probe: compiles every text of corpus/texts on a
// device's GL driver and pulls the logs. Not part of the app.
// Run: ./gradlew :typo-probe:probe -Pserial=<adb serial>
android {
	namespace = "de.markusfisch.android.shadereditor.typoprobe"
	compileSdk = 37

	defaultConfig {
		applicationId = "de.markusfisch.android.shadereditor.typoprobe"
		minSdk = 21
		targetSdk = 37
		versionCode = 1
		versionName = "1"
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	sourceSets {
		getByName("main") {
			assets.directories.add("corpus")
		}
	}

	androidResources {
		// The .src files (the mutated source files) are not needed on
		// the device.
		ignoreAssetsPatterns += listOf("!*.src", "!index.jsonl")
	}
}

abstract class Probe : DefaultTask() {
	@get:Inject
	abstract val exec: ExecOperations

	@get:InputFile
	abstract val adb: RegularFileProperty

	@get:InputFile
	abstract val apk: RegularFileProperty

	@get:Input
	abstract val serial: Property<String>

	@get:OutputDirectory
	abstract val results: DirectoryProperty

	@TaskAction
	fun run() {
		val adb = adb.get().asFile.absolutePath
		val s = serial.get()
		val pkg = "de.markusfisch.android.shadereditor.typoprobe"
		val remote = "/sdcard/Android/data/$pkg/files/results.jsonl"
		val out = results.get().file(
			s.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jsonl").asFile
		exec.exec { commandLine(adb, "-s", s, "install", "-r", "-t",
			apk.get().asFile.absolutePath) }
		try {
			exec.exec { commandLine(adb, "-s", s, "shell", "am", "instrument",
				"-w", "$pkg/.Probe") }
			exec.exec { commandLine(adb, "-s", s, "pull", remote,
				out.absolutePath) }
		} finally {
			exec.exec { commandLine(adb, "-s", s, "uninstall", pkg) }
		}
		logger.lifecycle("Results: $out")
	}
}

tasks.register<Probe>("probe") {
	group = "verification"
	description = "Compile the typo corpus on the device -Pserial=<serial>"
	dependsOn("assembleDebug")
	adb.set(androidComponents.sdkComponents.adb)
	apk.set(layout.buildDirectory.file(
		"outputs/apk/debug/typo-probe-debug.apk"))
	serial.set(providers.gradleProperty("serial"))
	results.set(layout.projectDirectory.dir("results"))
}
