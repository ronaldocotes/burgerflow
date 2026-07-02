package com.menuflow.repository.control

import com.menuflow.model.control.AiUsage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Ledger de uso de IA por tenant/mes (banco de CONTROLE). Faturamento por consumo.
 */
@Repository
interface AiUsageRepository : JpaRepository<AiUsage, UUID> {
    /** Linha do acumulado do mes corrente para o tenant (upsert do consumo). */
    fun findByTenantIdAndMonthYear(tenantId: UUID, monthYear: String): AiUsage?

    /** Todas as linhas de um mes — painel super-admin. Ordenadas por custo decrescente. */
    fun findAllByMonthYearOrderByEstimatedCostUsdMicrosDesc(monthYear: String): List<AiUsage>
}
