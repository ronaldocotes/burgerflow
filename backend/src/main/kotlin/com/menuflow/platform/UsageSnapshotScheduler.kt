package com.menuflow.platform

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Job noturno de snapshot de uso dos tenants.
 *
 * Roda às 03:00 (UTC) todos os dias — horário de baixo tráfego para o SaaS de
 * hamburguerias (encerram às 23–01h BRT). Padrão idêntico ao [ConversionDispatchJob]
 * e [CartRecoveryJob]: @Component separado do service para manter o service
 * testável sem acoplamento com o scheduler.
 *
 * Falha no job não é propagada — [UsageSnapshotService.snapshotAll] já é
 * fail-open por tenant e loga internamente.
 */
@Component
class UsageSnapshotScheduler(
    private val service: UsageSnapshotService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 3 * * *")
    fun run() {
        log.info("[snapshot-scheduler] iniciando job noturno de snapshot de uso")
        try {
            service.snapshotAll()
        } catch (e: Exception) {
            log.error("[snapshot-scheduler] erro inesperado no job: {}", e.message, e)
        }
    }
}
