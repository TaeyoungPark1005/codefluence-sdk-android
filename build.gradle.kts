plugins {
    id("com.android.library") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    `maven-publish`
}

// 게시 좌표는 확정 전이다(docs/SDK-PUBLISHING.md). 기본값은 로컬 검증용이며 -Pcodefluence.group 등으로 바꾼다.
group = (findProperty("codefluence.group") as String?) ?: "io.jocoding.codefluence"
version = (findProperty("codefluence.version") as String?) ?: "0.2.2"

android {
    namespace = "com.codefluence.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "sdk-android"
                pom {
                    name.set("Codefluence Android SDK")
                    description.set("Referral attribution SDK for Codefluence")
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("com.android.installreferrer:installreferrer:2.2")
    // Apps that sell in-app products already ship Play Billing; the SDK only reads purchases with it.
    compileOnly("com.android.billingclient:billing:7.1.1")
    // Play Integrity Standard API for the attested daily activity signal.
    implementation("com.google.android.play:integrity:1.4.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
