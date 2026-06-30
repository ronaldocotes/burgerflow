package com.menuflow.channels

import org.springframework.stereotype.Component

/**
 * Canal próprio (PDV, cardápio público, app). É um no-op: o pedido nasce e vive
 * dentro do MenuFlow, não há plataforma externa para notificar. Serve de fallback
 * padrão no [ChannelOrderService].
 */
@Component
class OwnChannelAdapter : ChannelOrderAdapter {
    override val channelType = ChannelType.OWN
}
