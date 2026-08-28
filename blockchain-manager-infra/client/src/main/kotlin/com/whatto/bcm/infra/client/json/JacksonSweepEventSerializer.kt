package com.whatto.bcm.infra.client.json

import com.whatto.bcm.domain.sweep.SweepEventPayload
import com.whatto.bcm.domain.sweep.SweepEventSerializer
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class JacksonSweepEventSerializer(
    private val objectMapper: ObjectMapper,
) : SweepEventSerializer {
    override fun serialize(event: SweepEventPayload): String = objectMapper.writeValueAsString(event)
}
