package com.whatto.bcm.admin

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class BcmAdminApplication

fun main(args: Array<String>) {
    runApplication<BcmAdminApplication>(*args)
}
