package com.whatto.bcm.infra.client.json

import com.whatto.bcm.domain.event.ChainEvent
import com.whatto.bcm.domain.event.ChainEventSerializer
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class JacksonChainEventSerializer(
    private val objectMapper: ObjectMapper,
) : ChainEventSerializer {
    override fun serialize(event: ChainEvent): String = objectMapper.writeValueAsString(event)
}
