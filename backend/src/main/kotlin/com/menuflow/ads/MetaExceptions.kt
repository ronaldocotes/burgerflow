package com.menuflow.ads

/**
 * Token da Meta invalido/expirado/revogado (Graph error code 190). Sinaliza ao
 * AdAccountService que a conexao deve ser rejeitada com uma mensagem clara ("cole
 * um token valido / reconecte") — NADA e salvo. Mapeado para 400 no service.
 */
class MetaTokenInvalidException(message: String) : RuntimeException(message)

/**
 * Falha generica ao falar com a Meta (rede, timeout, 5xx, ou erro Graph que nao seja
 * token invalido). Mapeado para 503 no service: e problema transitorio de dependencia
 * externa, nao erro do usuario.
 */
class MetaGraphException(message: String) : RuntimeException(message)

/**
 * A Meta esta limitando a taxa de chamadas (rate-limit / Business Use Case throttling —
 * code 613, subcode 80004, HTTP 429 ou header X-Business-Use-Case-Usage sinalizando
 * estouro). Distinta da [MetaGraphException] generica para o job de metricas apenas
 * PULAR aquela conta neste tick, sem estourar exceção que derrube a varredura dos demais
 * tenants/contas. Transitorio: a proxima coleta horaria tenta de novo.
 */
class MetaRateLimitException(message: String) : RuntimeException(message)
