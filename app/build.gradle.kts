plugins {
    id("com.android.application")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = providers.environmentVariable("CRID_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("CRID_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("CRID_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("CRID_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "cn.crid.next"
    compileSdk = 37

    defaultConfig {
        applicationId = "cn.crid.next"
        minSdk = 31
        targetSdk = 37
        versionCode = 30
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    bundle { language { enableSplit = false } }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            // Identical Apache 2.0 text is retained in the offline third-party notices.
            "META-INF/androidx/annotation/annotation/LICENSE.txt",
            "META-INF/androidx/collection/collection-ktx/LICENSE.txt",
            "META-INF/androidx/collection/collection/LICENSE.txt",
            "META-INF/androidx/lifecycle/lifecycle-common-java8/LICENSE.txt",
            "META-INF/androidx/lifecycle/lifecycle-common/LICENSE.txt",
            "META-INF/README.md",
            // JExcel's unused formula-name formatter is removed by R8; cached values still read normally.
            "functions.properties", "functions_da.properties", "functions_de.properties",
            "functions_en.properties", "functions_es.properties", "functions_fr.properties", "functions_nl.properties",
        )
    }
    sourceSets.getByName("androidTest").assets.directories.add(rootProject.file("tests/测试用例").path)
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
