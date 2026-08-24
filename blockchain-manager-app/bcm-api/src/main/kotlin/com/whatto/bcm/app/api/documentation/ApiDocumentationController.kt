package com.whatto.bcm.app.api.documentation

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class ApiDocumentationController {
    @GetMapping("/api-docs", "/api-docs/")
    fun documentation(): String = "forward:/api-docs/index.html"
}
