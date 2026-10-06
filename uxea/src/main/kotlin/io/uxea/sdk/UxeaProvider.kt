package io.uxea.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * O arranque numa linha.
 *
 * O Android cria os `ContentProvider` **antes** de chamar o `Application.onCreate`
 * da aplicação anfitriã. É por isso que este existe: quem nos instala escreve uma
 * entrada no manifesto e mais nada, e o SDK arranca sozinho.
 *
 * Não serve dados a ninguém, e todos os métodos devolvem vazio: é uma porta de
 * arranque, e não um fornecedor de conteúdos. O `authority` leva o nome do pacote
 * da aplicação (`${applicationId}`) para duas aplicações com o SDK poderem estar
 * instaladas ao mesmo tempo, que é uma regra do sistema e não uma preferência.
 */
class UxeaProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        Seguranca.executar("provider.onCreate") {
            context?.let { Uxea.iniciarDoManifesto(it) }
        }
        // Devolve `true` mesmo quando o arranque falha: um provedor que devolve
        // `false` faz o sistema tratar a aplicação como partida.
        return true
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
