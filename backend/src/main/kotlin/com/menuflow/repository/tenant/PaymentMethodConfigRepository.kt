package com.menuflow.repository.tenant

import com.menuflow.model.PaymentMethodConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface PaymentMethodConfigRepository : JpaRepository<PaymentMethodConfig, UUID> {

    /** Todas as formas na ordem de exibicao (config admin). */
    fun findAllByOrderBySortOrderAscLabelAsc(): List<PaymentMethodConfig>

    /** So as formas habilitadas (checkout publico). */
    fun findAllByEnabledTrueOrderBySortOrderAscLabelAsc(): List<PaymentMethodConfig>

    /** Busca pela chave natural (unica). */
    fun findByMethod(method: String): PaymentMethodConfig?
}
