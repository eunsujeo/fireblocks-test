package com.whatto.bcm.app.application.submission

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(TransactionSubmissionProperties::class)
class TransactionSubmissionConfig
