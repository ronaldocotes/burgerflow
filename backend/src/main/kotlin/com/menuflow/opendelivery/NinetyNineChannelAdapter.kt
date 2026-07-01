package com.menuflow.opendelivery

import com.menuflow.channels.ChannelType
import org.springframework.stereotype.Component

/**
 * Adapter do 99Food via Open Delivery (Fase 5.5a — stub). Registra-se no
 * ChannelOrderService pela propriedade [channelType] = NINETY_NINE.
 */
@Component
class NinetyNineChannelAdapter : OpenDeliveryChannelAdapter() {
    override val channelType = ChannelType.NINETY_NINE
    override fun platform() = "NINETY_NINE"
    // URL real: https://api.99app.com/v1 — confirmar na doc oficial antes de 5.5b.
    override fun baseUrl() = "https://api.99app.com/v1"
}
