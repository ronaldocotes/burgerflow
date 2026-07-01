package com.menuflow.opendelivery

import com.menuflow.channels.ChannelType
import org.springframework.stereotype.Component

/**
 * Adapter do Rappi via Open Delivery (Fase 5.5a — stub). Registra-se no
 * ChannelOrderService pela propriedade [channelType] = RAPPI. Antes desta fase o
 * enum RAPPI nao tinha adapter e caia no fallback OWN (no-op); agora tem o seu.
 */
@Component
class RappiChannelAdapter : OpenDeliveryChannelAdapter() {
    override val channelType = ChannelType.RAPPI
    override fun platform() = "RAPPI"
    // URL real: https://microservices.rappi.com.br/api/v2 — confirmar na doc antes de 5.5b.
    override fun baseUrl() = "https://microservices.rappi.com.br/api/v2"
}
