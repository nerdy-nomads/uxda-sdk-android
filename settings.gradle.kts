// O SDK Android da plataforma UX Data Analysis.
//
// Dois módulos, e a fronteira entre eles é a que interessa: `uxda` é o que vai
// dentro da aplicação de quem nos instala, e `exemplo` é a aplicação de ensaio
// que existe para se ver o SDK a correr num telemóvel a sério.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "uxda-sdk-android"
include(":uxda", ":exemplo")
