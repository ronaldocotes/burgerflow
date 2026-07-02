package com.menuflow.platform

/**
 * Marca um handler como dependente de um módulo opcional do MenuFlow. O
 * ModuleGateAspect intercepta a chamada e lança 403 se o módulo estiver
 * desabilitado para o tenant autenticado.
 *
 * Uso:
 *   @RequiresModule(ModuleKey.AI_COPILOT)
 *   @GetMapping("/copilot/suggest")
 *   fun suggest(...): ... = ...
 *
 * Gate triplo (defesa em profundidade):
 *   1. path-level em SecurityConfig (/admin/... → SUPER_ADMIN)
 *   2. @PreAuthorize no controller (papel mínimo exigido)
 *   3. @RequiresModule (módulo habilitado para o tenant)
 *
 * Não deve ser usado em rotas de core (PDV, KDS, caixa, cardápio) — apenas em
 * módulos opcionais listados em ModuleKey.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresModule(val value: ModuleKey)
