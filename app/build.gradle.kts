import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.GradleException
import java.util.Properties

apply(plugin = "com.android.application")
apply(plugin = "org.jetbrains.kotlin.android")

val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val hasReleaseSigning =
    keystoreProps.getProperty("storeFile") != null &&
        keystoreProps.getProperty("storePassword") != null &&
        keystoreProps.getProperty("keyAlias") != null &&
        keystoreProps.getProperty("keyPassword") != null
val wantsReleaseBuild = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
if (wantsReleaseBuild && !hasReleaseSigning) {
    throw GradleException(
        "Missing release signing config. Create keystore.properties from keystore.properties.example first."
    )
}

extensions.configure<ApplicationExtension>("android") {
    namespace = "com.scrollsnap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scrollsnap"
        minSdk = 26
        targetSdk = 35
        versionCode = 10000
        versionName = "1.0.0"
        buildConfigField("String", "GITHUB_OWNER", "\"li-junlei\"")
        buildConfigField("String", "GITHUB_REPO", "\"scrollsnap\"")
        buildConfigField("String", "GITHUB_DOCS_BRANCH", "\"master\"")
        buildConfigField("String", "RELEASES_URL", "\"https://github.com/li-junlei/scrollsnap/releases\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    add("implementation", "androidx.core:core-ktx:1.13.1")
    add("implementation", "androidx.appcompat:appcompat:1.7.0")
    add("implementation", "androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    add("implementation", "androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    add("implementation", "androidx.activity:activity-compose:1.9.1")
    add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    add("implementation", platform("androidx.compose:compose-bom:2024.06.00"))
    add("implementation", "androidx.compose.ui:ui")
    add("implementation", "androidx.compose.ui:ui-tooling-preview")
    add("implementation", "androidx.compose.material3:material3")
    add("implementation", "androidx.compose.material:material-icons-extended")
    add("implementation", "com.google.android.material:material:1.12.0")
    add("implementation", "com.quickbirdstudios:opencv:4.5.3.0")

    add("implementation", "dev.rikka.shizuku:api:13.1.5")
    add("implementation", "dev.rikka.shizuku:provider:13.1.5")

    add("debugImplementation", "androidx.compose.ui:ui-tooling")
    add("debugImplementation", "androidx.compose.ui:ui-test-manifest")
    add("testImplementation", "junit:junit:4.13.2")
    add("androidTestImplementation", "androidx.test.ext:junit:1.2.1")
    add("androidTestImplementation", "androidx.test:runner:1.6.2")
    add("androidTestImplementation", "androidx.test:core-ktx:1.6.1")
}
