plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.uxda.exemplo"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.uxda.exemplo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.4.0"
    }

    buildFeatures {
        compose = true
        viewBinding = false
    }

    // Duas variantes da **mesma** aplicação, e é o que torna a medição do cartão
    // 3.4 honesta: uma hora de uso com e sem SDK, no mesmo telemóvel, ao mesmo
    // tempo e com a mesma automação. Comparar duas aplicações diferentes, ou a
    // mesma em alturas diferentes, mede o ruído e não o SDK.
    // Duas dimensões. A primeira é com e sem SDK, para a medição do 3.4. A segunda
    // são **duas versões da mesma aplicação com o esquema alterado**, que é o
    // ensaio do 3.3: mesmo `applicationId`, `versionCode` diferente, e o esquema
    // reorganizado como um programador o reorganizaria numa versão nova.
    flavorDimensions += listOf("sdk", "esquema")
    productFlavors {
        create("v1") {
            dimension = "esquema"
            versionCode = 1
        }
        create("v2") {
            dimension = "esquema"
            versionCode = 2
            versionNameSuffix = "-v2"
        }
        create("com") {
            dimension = "sdk"
            applicationIdSuffix = ".com"
            versionNameSuffix = "-com-sdk"
            // A chave entra na compilação e **não fica no repositório**: uma chave
            // de ingestão versionada é uma chave que se usa por engano.
            manifestPlaceholders["uxdaChave"] = (project.findProperty("uxdaChave") as String?) ?: "uxda_des_por_definir"
            manifestPlaceholders["uxdaServidor"] = (project.findProperty("uxdaServidor") as String?) ?: "http://10.0.2.2:8710"
        }
        create("sem") {
            dimension = "sdk"
            applicationIdSuffix = ".sem"
            versionNameSuffix = "-sem-sdk"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["v1"].java.srcDir("src/v1/kotlin")
    sourceSets["v2"].java.srcDir("src/v2/kotlin")
    sourceSets["com"].java.srcDir("src/com/kotlin")
    sourceSets["sem"].java.srcDir("src/sem/kotlin")
}

dependencies {
    "comImplementation"(project(":uxda"))

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
