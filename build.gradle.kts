plugins {
    id("com.android.library") version "8.11.0" apply false
    id("com.android.application") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    // Desde o Kotlin 2.0 o compilador do Compose é um plugin à parte, e a versão
    // dele anda com a do Kotlin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}
