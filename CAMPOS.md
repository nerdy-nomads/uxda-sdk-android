# O que este SDK captura, campo a campo

<!-- GERADO por scripts/campos-capturados.py a partir do esquema e de finalidades.json. Não editar à mão. -->

A lista é **fechada**: o SDK só envia o que está aqui, e a ingestão recusa um evento com um campo que não esteja. Cada linha aponta para a linha do código, deste repositório e do outro SDK, que produz o campo, para quem lê confirmar no código e não confiar na nossa palavra (`RNF-PRI-11`). Há um ensaio em cada SDK que falha se o código enviar um campo que esta página não lista.

## O que nunca é capturado

- **O que a pessoa escreve.** Do campo mede-se o comportamento (foco, tempo, quantos caracteres, correções, erros), e nunca o valor, sem exceções e sem opção de configuração (`RNF-PRI-01`).
- **Identificadores diretos** (nome, conta, contacto, documento). Um valor que pareça direto, passado ao `identificar()`, é resumido no dispositivo antes de sair (`RNF-PRI-02`).
- **Capturas de ecrã, gravações, localização** (a geografia é o fuso horário), cookies de terceiros e impressão digital do dispositivo.

## A política de mascaramento

Tudo o que o SDK recebe do código da aplicação sai **mascarado por omissão**: o texto de uma mensagem sem chave e o valor de cada propriedade (nomes, citações, números, identificadores e correio substituídos por marcadores). Uma propriedade fora da lista não sai. Só a lista de permissões da instituição levanta a máscara, chave a chave, e **nunca levanta o chão**: correio, números longos e referências saem sempre mascarados, também nos nomes de ecrã, passo, evento e mensagem, nos atributos de teste e nos segmentos do endereço. E com `consentimento: "exigido"`, o SDK não faz nada até a aplicação transmitir o consentimento da pessoa.

## Os campos de cada evento

| Campo | Para quê | Web | Android |
|---|---|---|---|
| `event_id` | Identificador aleatório do evento, gerado no dispositivo, para a ingestão não contar duas vezes o mesmo evento quando o lote é reenviado. | [index.ts:234](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/index.ts#L234) | [Modelo.kt:48](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L48) |
| `anonymous_id` | Identificador aleatório e opaco do dispositivo, gerado pelo SDK e guardado só nele. Liga os eventos da mesma pessoa sem saber quem ela é. | [index.ts:237](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/index.ts#L237) | [Modelo.kt:49](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L49) |
| `user_id` | O pseudónimo que a aplicação passou ao identificar(), depois de autenticar a pessoa. Um valor que pareça direto (correio, telefone, nome) é resumido no dispositivo antes de sair. | [index.ts:402](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/index.ts#L402) | [Modelo.kt:69](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L69) |
| `device_id` | Identificador aleatório e opaco do dispositivo, gerado pelo SDK, que sobrevive ao terminar a sessão. | [index.ts:238](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/index.ts#L238) | [Modelo.kt:50](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L50) |
| `session_id` | Identificador aleatório da sessão técnica, que se renova ao fim de trinta minutos parada. | [index.ts:239](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/index.ts#L239) | [Modelo.kt:51](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L51) |
| `event_type` | O tipo de interação: ecrã, toque, foco, campo, submissão, erro, mensagem, e os restantes da lista fechada. | [index.ts:240](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/index.ts#L240) | [Modelo.kt:52](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L52) |
| `screen_key` | O ecrã, pelo caminho do endereço sem anfitrião, sem consulta e com os identificadores mascarados, ou pelo nome que a aplicação declarou. | [captura.ts:194](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/captura.ts#L194) | [Modelo.kt:53](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L53) |
| `element_key` | A identidade do elemento tocado: o atributo de teste, o caminho na árvore, o resumo do rótulo, o destino e o papel. Nunca o texto nem o valor. | [campos.ts:197](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L197) | [Modelo.kt:70](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L70) |
| `occurred_at` | O instante da interação, no relógio do dispositivo. | [index.ts:242](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/index.ts#L242) | [Modelo.kt:54](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L54) |
| `app_version` | A versão da aplicação anfitriã, para comparar versões. | [index.ts:222](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/index.ts#L222) | [Modelo.kt:55](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L55) |
| `platform` | A plataforma: web ou android. | [index.ts:222](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/index.ts#L222) | [Modelo.kt:58](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L58) |
| `identity_scope` | O âmbito do identificador anónimo (por aplicação). | [index.ts:245](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/index.ts#L245) | [Modelo.kt:59](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L59) |
| `capture_level` | O nível de captura em vigor quando o evento saiu: essencial, padrão ou detalhado. | [index.ts:246](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/index.ts#L246) | [Modelo.kt:60](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L60) |
| `properties` | As propriedades do evento, sempre da lista fechada abaixo. Números, booleanos e textos curtos; nunca conteúdo. | [campos.ts:201](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L201) | [Modelo.kt:75](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L75) |
| `message_key` | A chave de uma mensagem apresentada à pessoa, ou o nome de um evento marcado pela aplicação. | [campos.ts:315](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L315) | [Mensagens.kt:209](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Mensagens.kt#L209) |
| `message_kind` | A classe da mensagem: erro, aviso, sucesso ou informação. | [campos.ts:316](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L316) | [Mensagens.kt:210](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Mensagens.kt#L210) |
| `message_text_masked` | O texto de uma mensagem sem chave, mascarado no dispositivo: nomes, citações, números, identificadores e correio substituídos por marcadores. | [mensagens.ts:261](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/mensagens.ts#L261) | [Mensagens.kt:211](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Mensagens.kt#L211) |
| `duration_ms` | Uma duração medida no dispositivo (tempo à vista, espera, tempo num campo). | [campos.ts:200](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L200) | [Modelo.kt:71](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L71) |
| `os_name` | O sistema operativo, para segmentar. | [contexto.ts:77](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/contexto.ts#L77) | [Modelo.kt:65](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L65) |
| `os_version` | A versão principal do sistema operativo. | [contexto.ts:79](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/contexto.ts#L79) | [Modelo.kt:66](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L66) |
| `device_class` | A classe do dispositivo: telemóvel, tablet ou computador. | [contexto.ts:81](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/contexto.ts#L81) | [Modelo.kt:67](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L67) |
| `time_zone` | O fuso horário do dispositivo, que é toda a geografia do produto. Nunca o IP nem coordenadas. | [contexto.ts:84](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/contexto.ts#L84) | [Modelo.kt:68](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Modelo.kt#L68) |

## As propriedades, por grupo

### negocio

| Propriedade | Para quê | Web | Android |
|---|---|---|---|
| `valor_monetario` | Um valor que a instituição associa ao evento, para os indicadores financeiros. | [privacidade.ts:54](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/privacidade.ts#L54) | [Privacidade.kt:26](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Privacidade.kt#L26) |
| `moeda` | A moeda desse valor. | [privacidade.ts:54](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/privacidade.ts#L54) | [Privacidade.kt:26](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Privacidade.kt#L26) |
| `tipo_utilizador` | Um segmento que a instituição define. Sai mascarado por omissão. | [privacidade.ts:54](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/privacidade.ts#L54) | [Privacidade.kt:26](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Privacidade.kt#L26) |
| `segmento` | Um segmento que a instituição define. Sai mascarado por omissão. | [privacidade.ts:54](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/privacidade.ts#L54) | [Privacidade.kt:26](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Privacidade.kt#L26) |
| `canal` | O canal que a instituição define. Sai mascarado por omissão. | [privacidade.ts:54](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/privacidade.ts#L54) | [Privacidade.kt:26](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Privacidade.kt#L26) |
| `campanha` | A campanha que a instituição define. Sai mascarado por omissão. | [privacidade.ts:54](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/privacidade.ts#L54) | [Privacidade.kt:26](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Privacidade.kt#L26) |
| `experiencia` | A variante de uma experiência que a instituição define. | [privacidade.ts:54](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/privacidade.ts#L54) | [Privacidade.kt:26](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Privacidade.kt#L26) |

### toque

| Propriedade | Para quê | Web | Android |
|---|---|---|---|
| `toque_x` | Rastreio individual: a posição do toque na janela, em percentagem da largura. | [toques.ts:287](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/toques.ts#L287) | [Toques.kt:84](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Toques.kt#L84) |
| `toque_y` | Rastreio individual: a posição do toque na janela, em percentagem da altura. | [toques.ts:288](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/toques.ts#L288) | [Toques.kt:85](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Toques.kt#L85) |
| `zona` | A zona do ecrã onde caiu um toque sem alvo. | [toques.ts:298](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/toques.ts#L298) | [Toques.kt:133](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Toques.kt#L133) |
| `repeticoes` | Quantas vezes o mesmo elemento foi tocado seguido. | [toques.ts:206](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/toques.ts#L206) | [Toques.kt:204](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Toques.kt#L204) |
| `intervalo_ms` | O intervalo entre toques repetidos. | [toques.ts:207](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/toques.ts#L207) | [Toques.kt:205](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Toques.kt#L205) |
| `alvo_desativado` | Se o toque caiu num elemento desativado. | [toques.ts:318](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/toques.ts#L318) | [Toques.kt:149](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Toques.kt#L149) |
| `em_carregamento` | Se o toque foi dado com um pedido da aplicação em curso. | [toques.ts:327](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/toques.ts#L327) | [Toques.kt:172](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Toques.kt#L172) |
| `alvo_x` | Rastreio individual: a posição do toque dentro do elemento, em percentagem. | [toques.ts:112](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/toques.ts#L112) | [Toques.kt:106](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Toques.kt#L106) |
| `alvo_y` | Rastreio individual: a posição do toque dentro do elemento, em percentagem. | [toques.ts:112](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/toques.ts#L112) | [Toques.kt:107](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Toques.kt#L107) |
| `alvo_caixa` | Rastreio individual: a caixa do elemento na janela, de onde se reconstrói o esquema do ecrã sem captura nenhuma. | [toques.ts:308](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/toques.ts#L308) | [Toques.kt:108](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Toques.kt#L108) |
| `visor_largura` | Rastreio individual: a largura do visor, para sobrepor ecrãs de tamanhos diferentes. | [toques.ts:289](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/toques.ts#L289) | [Toques.kt:86](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Toques.kt#L86) |
| `visor_altura` | A altura do visor, para o rastreio individual e para a profundidade de deslocamento. | [deslocamento.ts:116](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/deslocamento.ts#L116) | [Deslocamento.kt:189](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Deslocamento.kt#L189) |
| `ordem_na_sequencia` | Rastreio individual: a ordem da interação dentro do ecrã. | [toques.ts:291](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/toques.ts#L291) | [Toques.kt:88](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Toques.kt#L88) |

### campo

| Propriedade | Para quê | Web | Android |
|---|---|---|---|
| `hesitacao_ms` | O tempo entre entrar num campo e começar a escrever. | [campos.ts:184](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L184) | [Campos.kt:95](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Campos.kt#L95) |
| `tempo_fundo_ms` | O tempo em segundo plano enquanto o campo estava aberto. | [campos.ts:185](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L185) | [Campos.kt:96](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Campos.kt#L96) |
| `caracteres_escritos` | Quantos caracteres foram escritos. A contagem, nunca os caracteres. | [campos.ts:178](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L178) | [Campos.kt:90](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Campos.kt#L90) |
| `caracteres_apagados` | Quantos caracteres foram apagados. | [campos.ts:179](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L179) | [Campos.kt:91](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Campos.kt#L91) |
| `origem` | Como o campo foi preenchido: escrito, colado ou preenchido automaticamente. | [campos.ts:186](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L186) | [Campos.kt:97](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Campos.kt#L97) |
| `visitado_vazio` | Se a pessoa saiu do campo sem escrever nada. | [campos.ts:187](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L187) | [Campos.kt:98](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Campos.kt#L98) |
| `regressos` | Quantas vezes a pessoa voltou ao campo. | [campos.ts:180](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L180) | [Campos.kt:92](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Campos.kt#L92) |
| `ordem` | A ordem em que o campo foi visitado. | [campos.ts:154](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L154) | [Campos.kt:93](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Campos.kt#L93) |
| `ordem_prevista` | A ordem do campo no formulário. | [campos.ts:182](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L182) | [Campos.kt:94](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Campos.kt#L94) |
| `estado_na_submissao` | O estado do campo quando o formulário foi submetido: preenchido, vazio ou com erro. | [campos.ts:189](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L189) | [Campos.kt:100](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Campos.kt#L100) |
| `tentativas_ate_resolver` | Quantas submissões até o erro do campo desaparecer. | [campos.ts:188](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L188) | [Campos.kt:99](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Campos.kt#L99) |
| `fase` | A fase do campo em que o evento aconteceu. | [campos.ts:190](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/campos.ts#L190) | [Campos.kt:101](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Campos.kt#L101) |

### progressao

| Propriedade | Para quê | Web | Android |
|---|---|---|---|
| `passo` | O passo da tarefa declarado pela aplicação. | [mensagens.ts:236](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/mensagens.ts#L236) | [Mensagens.kt:186](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Mensagens.kt#L186) |
| `passo_anterior` | O passo de onde a pessoa veio. | [progressao.ts:93](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/progressao.ts#L93) | [Progressao.kt:39](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Progressao.kt#L39) |
| `estado` | O estado com que a tentativa terminou: sucesso, erro, abandonado ou expirado. | [progressao.ts:115](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/progressao.ts#L115) | [Progressao.kt:61](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Progressao.kt#L61) |
| `campo_abandono` | A identidade do último campo em que a pessoa esteve antes de abandonar. | [progressao.ts:120](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/progressao.ts#L120) | [Progressao.kt:66](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Progressao.kt#L66) |
| `mudanca` | O que mudou no ambiente: rede, orientação, conectividade ou regresso ao primeiro plano. | [progressao.ts:66](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/progressao.ts#L66) | [Progressao.kt:77](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Progressao.kt#L77) |
| `valor` | O valor dessa mudança, de uma lista curta (por exemplo, 4g ou retrato). | [progressao.ts:66](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/progressao.ts#L66) | [Progressao.kt:77](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Progressao.kt#L77) |
| `campos_preenchidos` | Na submissão, quantos campos estavam preenchidos. | [captura.ts:280](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/captura.ts#L280) | [Captura.kt:506](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Captura.kt#L506) |
| `campos_vazios` | Na submissão, quantos campos estavam vazios. | [captura.ts:281](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/captura.ts#L281) | [Captura.kt:507](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Captura.kt#L507) |
| `campos_com_erro` | Na submissão, quantos campos tinham erro. | [captura.ts:282](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/captura.ts#L282) | [Captura.kt:508](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Captura.kt#L508) |

### mensagem

| Propriedade | Para quê | Web | Android |
|---|---|---|---|
| `origem_mensagem` | De onde veio a mensagem: chave declarada, texto observado, rede ou servidor. | [mensagens.ts:227](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/mensagens.ts#L227) | [Mensagens.kt:181](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Mensagens.kt#L181) |
| `classe_erro` | A classe de um erro: validação, operação ou sistema. | [mensagens.ts:231](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/mensagens.ts#L231) | [Uxda.kt:408](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Uxda.kt#L408) |
| `codigo_http` | O código de uma resposta de erro da rede da aplicação. | [index.ts:436](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/index.ts#L436) | [Uxda.kt:410](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Uxda.kt#L410) |
| `operacao` | A operação a que a mensagem ou o erro se refere: o primeiro segmento do caminho, ou o nome que a aplicação deu. Sai mascarada por omissão. | [index.ts:434](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/index.ts#L434) | [Uxda.kt:409](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Uxda.kt#L409) |
| `visivel` | Se a mensagem foi vista pela pessoa, ou se o erro aconteceu sem ninguém o ver. | [mensagens.ts:228](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/mensagens.ts#L228) | [Uxda.kt:436](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/Uxda.kt#L436) |
| `grupo_mensagem` | O resumo do esqueleto da mensagem mascarada, para agrupar variantes da mesma mensagem. | [mensagens.ts:247](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/mensagens.ts#L247) | [Mensagens.kt:193](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Mensagens.kt#L193) |
| `campo_associado` | A identidade do campo a que a mensagem de erro se refere. | [mensagens.ts:234](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/mensagens.ts#L234) | [Mensagens.kt:185](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Mensagens.kt#L185) |

### deslocamento

| Propriedade | Para quê | Web | Android |
|---|---|---|---|
| `profundidade` | Rastreio individual: até onde a pessoa deslocou o ecrã, em percentagem. | [deslocamento.ts:114](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/deslocamento.ts#L114) | [Deslocamento.kt:187](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Deslocamento.kt#L187) |
| `alcance_ms` | O tempo até chegar a essa profundidade. | [deslocamento.ts:115](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/deslocamento.ts#L115) | [Deslocamento.kt:188](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Deslocamento.kt#L188) |
| `visor_altura` | A altura do visor, para o rastreio individual e para a profundidade de deslocamento. | [deslocamento.ts:116](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/captura/deslocamento.ts#L116) | [Deslocamento.kt:189](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/captura/Deslocamento.kt#L189) |

## O envelope de cada lote

| Campo | Para quê | Web | Android |
|---|---|---|---|
| `versao_protocolo` | A versão do protocolo do lote. | [fila.ts:143](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/fila/fila.ts#L143) | [Fila.kt:99](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/fila/Fila.kt#L99) |
| `sdk` | O nome do SDK que enviou o lote (uxda-sdk-js ou uxda-sdk-android). | [fila.ts:144](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/fila/fila.ts#L144) | [Fila.kt:100](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/fila/Fila.kt#L100) |
| `versao_sdk` | A versão desse SDK, para saber que versões estão em uso e quem avisar antes de deixar cair uma. | [fila.ts:145](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/fila/fila.ts#L145) | [Fila.kt:101](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/fila/Fila.kt#L101) |
| `enviado_em` | O instante do envio, no relógio do dispositivo, para corrigir o desvio do relógio. | [fila.ts:146](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/fila/fila.ts#L146) | [Fila.kt:102](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/fila/Fila.kt#L102) |
| `eventos` | Os eventos do lote, cada um com os campos e as propriedades acima. | [fila.ts:147](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/fila/fila.ts#L147) | [Fila.kt:103](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/fila/Fila.kt#L103) |
| `erros_sdk` | Os erros internos do SDK desde o último lote: o sítio, o nome do erro e quantas vezes. Nunca a mensagem. | [fila.ts:148](https://github.com/nerdy-nomads/uxda-sdk-js/blob/master/src/fila/fila.ts#L148) | [Fila.kt:105](https://github.com/nerdy-nomads/uxda-sdk-android/blob/master/uxda/src/main/kotlin/io/uxda/sdk/fila/Fila.kt#L105) |

## O que a ingestão acrescenta, e não sai do dispositivo

- `project_id`: O espaço de dados (aplicação e ambiente). Escreve-o a ingestão, a partir da chave; o dispositivo não o conhece.
- `organization_id`: A organização da chave. Escreve-o a ingestão; o dispositivo não o conhece.
- `received_at`: O instante em que a ingestão recebeu o lote. Escreve-o a ingestão.
- `clock_offset_ms`: A diferença entre o relógio do dispositivo e o da ingestão, calculada pela ingestão a partir do instante de envio, para corrigir o instante.
- `sdk_name`, `sdk_version`: copiados do envelope para cada evento, para saber que versões estão em uso.
- `written_at`: o instante em que o evento foi escrito no armazenamento.
