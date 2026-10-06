// GERADO por scripts/sync-schema.sh a partir de uxea-core/event/schema.json. Não editar à mão.
package io.uxea.sdk

/** As listas do esquema que o SDK precisa em tempo de execução (cartão 18.1). */
internal object EsquemaGerado {
    /** Todas as propriedades permitidas. Uma chave fora daqui não sai do dispositivo. */
    val PERMITIDAS: Set<String> = setOf(
        "alcance_ms",
        "alvo_caixa",
        "alvo_desativado",
        "alvo_x",
        "alvo_y",
        "campanha",
        "campo_abandono",
        "campo_associado",
        "campos_com_erro",
        "campos_preenchidos",
        "campos_vazios",
        "canal",
        "caracteres_apagados",
        "caracteres_escritos",
        "classe_erro",
        "codigo_http",
        "em_carregamento",
        "estado",
        "estado_na_submissao",
        "experiencia",
        "fase",
        "grupo_mensagem",
        "hesitacao_ms",
        "intervalo_ms",
        "moeda",
        "mudanca",
        "operacao",
        "ordem",
        "ordem_na_sequencia",
        "ordem_prevista",
        "origem",
        "origem_mensagem",
        "passo",
        "passo_anterior",
        "profundidade",
        "regressos",
        "repeticoes",
        "segmento",
        "tempo_fundo_ms",
        "tentativas_ate_resolver",
        "tipo_utilizador",
        "toque_x",
        "toque_y",
        "valor",
        "valor_monetario",
        "visitado_vazio",
        "visivel",
        "visor_altura",
        "visor_largura",
        "zona",
    )

    /** As propriedades cujo valor é uma chave de elemento, e não texto. */
    val IDENTIFICADOR: Set<String> = setOf(
        "campo_abandono",
        "campo_associado",
    )
}
