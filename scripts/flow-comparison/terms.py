"""Short definitions for technical terms used in the comparison guide."""
from html import escape
import re


DEFINITIONS = {
    'DLQ': 'Dead Letter Queue. 반복 처리에 실패하거나 정상 처리할 수 없는 메시지를 따로 보관하는 큐입니다. 원인을 확인한 뒤 재처리할 때 사용합니다. 여기서는 SDK에 요구하는 실패 보관 방식의 예시이며, 제공 여부는 확인이 필요합니다.',
    'Inbox': '수신한 이벤트 원문과 처리 상태를 보관하는 저장소입니다. 먼저 저장한 뒤 수신 성공을 응답하고, 원장 반영이 실패하면 저장된 이벤트로 다시 처리합니다.',
    'relay': '발송 작업입니다. Outbox에 저장된 이벤트를 읽어 Kafka 같은 전달 시스템으로 보내고, 성공 여부와 재시도 상태를 기록합니다.',
    '멱등': '같은 요청이나 이벤트를 여러 번 처리해도 결과가 한 번 처리했을 때와 같도록 하는 성질입니다. CORE에서는 같은 eventId의 이벤트로 원장을 두 번 반영하지 않게 합니다.',
    '원자적': '여러 변경을 모두 함께 저장하거나, 하나라도 실패하면 모두 취소하는 성질입니다. 거래 상태만 바뀌고 보낼 이벤트가 빠지는 일을 막습니다.',
    '2xx': 'HTTP 성공 응답 코드입니다. 이 웹훅 계약에서는 CORE가 수신 원문을 저장했다는 뜻이며, 원장 반영 완료를 뜻하지 않습니다.',
    '4xx': '요청 내용이나 인증 등 요청 측 문제를 나타내는 HTTP 응답 코드입니다. 재시도 여부는 개별 코드와 API 계약에 따라 판단합니다.',
    '5xx': '요청을 처리하는 서버 측 오류를 나타내는 HTTP 응답 코드입니다. 재시도 여부와 간격은 API 계약에 따라 정합니다.',
}


def explain(text):
    pattern = r'(?<![A-Za-z0-9_])(' + '|'.join(map(re.escape, DEFINITIONS)) + r')(?![A-Za-z0-9_])'
    return re.sub(pattern, lambda match: '<button type="button" class="term-tooltip" title="' +
                  escape(DEFINITIONS[match[0]], quote=True) + '">' + match[0] + '</button>', text)
