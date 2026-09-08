# O SDK arranca por um ContentProvider declarado no manifesto, e é encontrado por
# nome: sem esta regra, o R8 de quem nos instala remove-o e o SDK nunca arranca.
-keep class io.uxda.sdk.UxdaProvider { *; }
-keep class io.uxda.sdk.Uxda { public *; }

# A leitura da árvore semântica do Compose é por reflexão, e degrada em silêncio
# quando não encontra os nomes. Não se guarda nada do Compose aqui de propósito:
# quem usa Compose já o guarda, e quem não usa não o tem.
