import java.util.Properties

plugins {
    id("com.android.application")
}

// keystore.properties e criado por scripts/build-install.ps1 (storeFile, storePassword, keyAlias, keyPassword).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.allan.nottoday"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.allan.nottoday"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // `-PtestApp` gera uma segunda instalação (outro pacote) para testar "app novo suspenso" no emulador.
            if (project.hasProperty("testApp")) applicationIdSuffix = ".testapp"
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false // debuggable permitiria `run-as` e leitura do PIN/prefs.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
