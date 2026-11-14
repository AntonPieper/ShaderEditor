plugins {
	alias(libs.plugins.android.application)
}

// Languages of the app's own translations (the values-* folders in
// src/main/res, English being values). Libraries ship strings for about 85
// languages; only these are packaged. Add a new translation here too;
// LocaleFiltersTest fails when one is missing.
val appLocales = listOf("en", "ru", "uk", "zh", "zh-rTW")

android {
	namespace = "de.markusfisch.android.shadereditor"

	compileSdk = 37

	defaultConfig {
		minSdk = 23
		targetSdk = compileSdk

		versionCode = 94
		versionName = "2.36.3"

		vectorDrawables {
			useSupportLibrary = true
		}
	}

	signingConfigs {
		create("release") {
			keyAlias = System.getenv("ANDROID_KEY_ALIAS")
			keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
			storePassword = System.getenv("ANDROID_STORE_PASSWORD")
			storeFile = System.getenv("ANDROID_KEYFILE")?.let { file(it) }
		}
	}

	buildTypes {
		debug {
			applicationIdSuffix = ".debug"
		}

		release {
			isMinifyEnabled = true
			isShrinkResources = true
			signingConfig = signingConfigs["release"]
		}
	}

	androidResources {
		localeFilters += appLocales
	}

	testOptions {
		unitTests.all {
			it.systemProperty("app.locales", appLocales.joinToString(","))
		}
	}

	sourceSets {
		// licenses/fonts/<font>.txt ship as assets/fonts/<font>.txt
		getByName("main").assets.directories.add("../licenses")
	}

	buildFeatures {
		buildConfig = true
		viewBinding = true
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
}

dependencies {
	implementation(libs.androidx.appcompat)
	implementation(libs.material)
	implementation(libs.androidx.preference)

	implementation(libs.androidx.camera.core)
	implementation(libs.androidx.camera.camera2)
	implementation(libs.androidx.camera.lifecycle)
	compileOnly(libs.androidx.concurrent.futures)

	debugImplementation(libs.leakcanary.android)

	testImplementation(libs.junit)
}
