package com.menuflow.controller

import com.menuflow.dto.DeliveryOrderResponse
import com.menuflow.dto.RouteAssignRequest
import com.menuflow.dto.RouteOptimizeRequest
import com.menuflow.dto.RouteOptimizeResponse
import com.menuflow.service.RouteOptimizationService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Roteirizacao de multiplas entregas (issue #4). Gestao da frota planeja e confirma a
 * rota de UM motoboy da FROTA que sai com N pedidos. RBAC alinhado ao restante do
 * delivery: planejar (otimizar) e leitura de gestao (OPERATOR/MANAGER/ADMIN);
 * confirmar (grava sequencia + atribui motoboy) espelha o /assign existente
 * (OPERATOR/ADMIN). DRIVER nao entra: o motoboy so LE a rota via GET /delivery/orders/my.
 */
@RestController
@RequestMapping("/delivery/route")
@PreAuthorize("hasAnyRole('OPERATOR','MANAGER','ADMIN')")
class DeliveryRouteController(
    private val routeOptimizationService: RouteOptimizationService,
) {

    /** F1 — ordem otima STATELESS (nao grava). Devolve as paradas na ordem de visita. */
    @PostMapping("/optimize")
    fun optimize(@Valid @RequestBody req: RouteOptimizeRequest): RouteOptimizeResponse =
        routeOptimizationService.optimize(req)

    /**
     * F2 — confirma a rota (na ordem enviada) atribuindo os pedidos a um motoboy da
     * FROTA e gravando a sequencia. Devolve os pedidos ja na ordem da rota, com
     * deliverySequence — o mesmo shape que o app do motoboy consome.
     */
    @PostMapping("/assign")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    fun assign(@Valid @RequestBody req: RouteAssignRequest): List<DeliveryOrderResponse> =
        routeOptimizationService.assignRoute(req)
}
