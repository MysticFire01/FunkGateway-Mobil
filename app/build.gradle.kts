plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    packaging {
        resources.pickFirsts.add("org/spongycastle/x509/CertPathReviewerMessages_de.properties")
    }
    namespace = "org.funkgateway.mobile"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "org.funkgateway.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1030
        versionName = "0.1.30"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Nativer Mumble-Client (Humla)
    implementation(files("libs/humla-debug.aar"))
    implementation("com.google.protobuf:protobuf-java:3.11.4")
    implementation("com.madgag.spongycastle:core:1.51.0.0")
    implementation("com.googlecode.javacpp:javacpp:0.7")
    implementation("org.jetbrains:annotations:18.0.0")
    implementation("org.minidns:minidns-hla:0.3.4")
    implementation("org.minidns:minidns-android21:0.3.4")
    implementation("com.google.guava:guava:28.2-android")
    implementation("androidx.core:core:1.15.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// APK nach jedem erfolgreichen Debug-Build zusätzlich
// unter dem Namen für GitHub und den Mobile-Updater bereitstellen.
val prepareFunkGatewayApk by tasks.registering(Copy::class) {
    group = "build"
    description = "Erstellt FunkGateway-Mobile.apk für GitHub."
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("release"))
    rename { "FunkGateway-Mobile.apk" }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(prepareFunkGatewayApk)
}
