# O SDK publica-se já encolhido pelo R8. Cartão 17.3, RNF-SDK-03.
#
# O inquérito da fase 14 levou o código do SDK de 187 para 305 KB de dex, e o orçamento
# de 300 KB esteve vermelho cinco dias sem ninguém o ver. O que pesava não era o
# comportamento: eram os nomes, os metadados do Kotlin e a informação de depuração das
# classes internas, que nenhuma aplicação anfitriã usa. Encolher a biblioteca tira isso e
# deixa a API pública exatamente como estava.
#
# **A API pública guarda-se inteira**, com os metadados do Kotlin (sem eles, quem chama em
# Kotlin perdia os argumentos por omissão e o `Uxea.x()` de um `object`).
-keep class io.uxea.sdk.Uxea { public *; }
-keep class io.uxea.sdk.UxeaProvider { *; }
-keep class io.uxea.sdk.UxeaOkHttp { public *; }
-keep class io.uxea.sdk.Opcoes { *; }
-keep class io.uxea.sdk.Terminal { *; }
-keep class io.uxea.sdk.Tipos { *; }
-keep interface io.uxea.sdk.captura.VistaDoSdk
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# O traço de um erro tem de continuar a apontar para a linha certa.
-keepattributes SourceFile,LineNumberTable

# O que é só de compilação: quem usa fragmentos, Compose ou OkHttp já os tem; quem não
# usa, não os tem, e o SDK degrada em silêncio.
-dontwarn androidx.fragment.**
-dontwarn androidx.compose.**
-dontwarn okhttp3.**
-dontwarn okio.**
