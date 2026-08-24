package com.whatto.bcm.testsupport

import com.whatto.bcm.testsupport.chain.LocalChainCommand
import com.whatto.bcm.testsupport.chain.LocalControlCommand
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
@ConfigurationPropertiesScan
class TestSupportApplication {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "chain") {
        require(LocalChainCommand.requested(args)) { "chain command does not accept arguments" }
        LocalChainCommand.run()
        return
    }
    if (args.firstOrNull() == "control") {
        require(LocalControlCommand.requested(args)) { "unsupported local control command" }
        println(LocalControlCommand.execute(args[1]))
        return
    }
    runApplication<TestSupportApplication>(*args)
}
