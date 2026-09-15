package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.exception.VendorApiException
import org.springframework.http.HttpMethod
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * 사용자 행위 서명 흐름 — 공식 OpenAPI 1.1018.3의 `POST /auth/action/init`·`POST /auth/action`.
 * init에는 실제로 보낼 본문 바이트를 그대로 `userActionPayload` 문자열로 넣고, 응답 challenge를 Key credential로 서명한 뒤
 * `/auth/action`으로 userAction 토큰을 받는다. 토큰은 이어지는 한 요청의 `X-DFNS-USERACTION` 헤더에만 쓴다.
 * allowCredentials.key 목록이 있으면 설정된 credential ID가 그 안에 있어야 서명한다 — 서버가 받지 않을 키로 서명하지 않는다.
 * 토큰 유효기간·재사용 가능성은 명세에 없으므로 저장·재사용하지 않는다.
 */
internal class DfnsUserActionClient(
    private val http: DfnsHttp,
    private val signer: DfnsCredentialSigner,
    private val objectMapper: ObjectMapper,
) {
    fun userAction(
        method: HttpMethod,
        path: String,
        body: ByteArray,
    ): String {
        val challenge = createChallenge(method, path, body)
        val assertion = signer.sign(challenge.challenge)
        val request =
            objectMapper.writeValueAsBytes(
                linkedMapOf(
                    "challengeIdentifier" to challenge.challengeIdentifier,
                    "firstFactor" to
                        linkedMapOf(
                            "kind" to "Key",
                            "credentialAssertion" to
                                linkedMapOf(
                                    "credId" to assertion.credId,
                                    "clientData" to assertion.clientData,
                                    "signature" to assertion.signature,
                                ),
                        ),
                ),
            )
        val response = http.call(SIGN_OPERATION, HttpMethod.POST, ACTION_PATH, request).requireSuccess(SIGN_OPERATION)
        return requiredText(parse(SIGN_OPERATION, response), "userAction", SIGN_OPERATION)
    }

    private fun createChallenge(
        method: HttpMethod,
        path: String,
        body: ByteArray,
    ): DfnsUserActionChallenge {
        require(path.startsWith("/")) { "Dfns user action path must be absolute" }
        val request =
            objectMapper.writeValueAsBytes(
                linkedMapOf(
                    "userActionServerKind" to "Api",
                    "userActionHttpMethod" to method.name(),
                    "userActionHttpPath" to path,
                    "userActionPayload" to body.toString(Charsets.UTF_8),
                ),
            )
        val response = http.call(INIT_OPERATION, HttpMethod.POST, INIT_PATH, request).requireSuccess(INIT_OPERATION)
        val node = parse(INIT_OPERATION, response)
        val challenge = requiredText(node, "challenge", INIT_OPERATION)
        val identifier = requiredText(node, "challengeIdentifier", INIT_OPERATION)
        val allowedKeys = node.path("allowCredentials").path("key")
        if (allowedKeys.isArray) {
            val allowedIds = allowedKeys.mapNotNull { it.path("id").takeIf(JsonNode::isString)?.asString() }
            if (signer.credentialId !in allowedIds) {
                throw VendorApiException(
                    INIT_OPERATION,
                    HTTP_OK,
                    IllegalStateException("Dfns challenge does not allow the configured key credential"),
                )
            }
        }
        return DfnsUserActionChallenge(challenge, identifier)
    }

    private fun parse(
        operation: String,
        body: ByteArray,
    ): JsonNode =
        try {
            objectMapper.readTree(body)
        } catch (exception: RuntimeException) {
            throw VendorApiException(operation, HTTP_OK, exception)
        }

    private fun requiredText(
        node: JsonNode,
        field: String,
        operation: String,
    ): String {
        val value = node.path(field)
        if (!value.isString || value.asString().isBlank()) {
            throw VendorApiException(operation, HTTP_OK, IllegalStateException("Dfns $operation 응답 결손: $field"))
        }
        return value.asString()
    }

    companion object {
        const val INIT_PATH = "/auth/action/init"
        const val ACTION_PATH = "/auth/action"
        private const val INIT_OPERATION = "dfnsUserActionInit"
        private const val SIGN_OPERATION = "dfnsUserActionSign"
        private const val HTTP_OK = 200
    }
}

private data class DfnsUserActionChallenge(
    val challenge: String,
    val challengeIdentifier: String,
)
