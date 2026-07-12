package com.menuflow.platform

import com.menuflow.IntegrationTestBase
import com.menuflow.dispatch.GoogleApiKeyProvider
import com.menuflow.model.control.PlatformApiKeyProviderType
import com.menuflow.repository.control.PlatformApiKeyRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Achado M1 (Centuriao): o cache do GoogleApiKeyProvider nao pode ser repovoado ANTES do
 * commit. Se a transacao de escrita rolar, o cache NAO pode servir por 5 min uma chave que
 * nunca existiu. Aqui forcamos um rollback apos o upsert e provamos que o provider continua
 * resolvendo a chave ANTIGA (commitada), nao a nova (revertida).
 */
class PlatformApiKeyServiceCacheIT @Autowired constructor(
    private val service: PlatformApiKeyService,
    private val provider: GoogleApiKeyProvider,
    private val repository: PlatformApiKeyRepository,
    @Qualifier("controlTransactionManager") txManager: PlatformTransactionManager,
) : IntegrationTestBase() {

    private val txTemplate = TransactionTemplate(txManager)

    @BeforeEach
    fun reset() {
        repository.deleteAll()
        provider.invalidate()
    }

    @Test
    fun `rollback do upsert nao deixa o cache com a chave nova`() {
        // Estado inicial commitado: K1 vale.
        service.upsert(PlatformApiKeyProviderType.GOOGLE_MAPS, "K1VALUE00001")
        assertEquals("K1VALUE00001", provider.resolve())

        // Uma tx que faz upsert de K2 e ENTAO rola (simula corrida no indice unico / erro).
        // upsert e @Transactional(REQUIRED) -> junta-se a esta tx; a excecao reverte tudo.
        runCatching {
            txTemplate.execute {
                service.upsert(PlatformApiKeyProviderType.GOOGLE_MAPS, "K2VALUE00002")
                throw RuntimeException("boom: forca rollback apos o save")
            }
        }

        // O invalidate() estava agendado para POS-COMMIT; como rolou, nunca disparou e o
        // describeUncached nao repovoou o cache -> o provider continua resolvendo K1.
        assertEquals("K1VALUE00001", provider.resolve())
        // E o banco: so K1 ativo (K2 revertido).
        assertEquals(1, repository.findAll().count { it.active })
        assertEquals("K1VALUE00001", provider.resolve())
    }
}
