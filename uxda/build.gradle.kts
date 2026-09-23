plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.uxda.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            // Publica-se encolhido, com a API pública guardada (`proguard-rules.pro`).
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["test"].java.srcDir("src/test/kotlin")
}

dependencies {
    // **Nenhuma dependência de execução.** O orçamento do RNF-SDK-03 são 300 KB
    // para o SDK inteiro, e não sobrevive a uma árvore de dependências. O que está
    // aqui é de compilação apenas: a aplicação que use Compose ou OkHttp ganha a
    // integração, e a que não usa não leva um byte deles por nossa causa.
    compileOnly("androidx.compose.ui:ui:1.7.6")
    compileOnly("androidx.fragment:fragment:1.8.5")
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    // A `RecyclerView` é a única forma pública de saber quanto conteúdo há debaixo
    // do ecrã numa lista que virtualiza (cartão 9.1). O `View` esconde os três
    // números em métodos protegidos, e ela volta a torná-los públicos. `compileOnly`
    // como os outros: quem não a usa não leva um byte dela.
    compileOnly("androidx.recyclerview:recyclerview:1.3.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
