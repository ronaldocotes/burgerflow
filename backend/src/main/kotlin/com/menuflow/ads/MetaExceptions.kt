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
