"""Requested SDK delivery guarantees; these are not verified SDK capabilities."""
from terms import explain


def requirement(link):
    sources = [
        ('../wallet-sdk/_reference/fund-flows--webhooks.html', 'SDK 웹훅 문서'),
        ('../design/02-bcm-flow.md', '현행 BCM 이벤트 흐름'),
        ('../api/openapi.yaml', 'BCM 이벤트 완료 확인 계약'),
    ]
    return explain('''<article class="event-requirement" id="sdk-event-delivery" aria-labelledby="delivery-requirement-title">
<p class="requirement-status">SDK 연동 요구사항 · 큐 대응 기능 검증 필요</p>
<h3 id="delivery-requirement-title">SDK가 큐에 상응하는 이벤트 전달을 보장해야 한다</h3>
<p>외부 Queue를 제거하려면 SDK가 이벤트 영속 보관·재전송·전달 순서·실패 복구를 책임져야 한다.
웹훅이 있다는 이유만으로 이 조건을 충족한 것으로 보지 않는다. Kafka 같은 특정 제품 사용을 요구하는 것이 아니라 동등한 동작과 장애 복구를 요구한다.</p>
<h4>현재 확인한 범위</h4>
<p>SDK 문서는 웹훅과 중복 수신·재시도를 설명한다. 다만 4xx와 5xx를 구분하지 않고 재시도한다고 명시한다.
이벤트의 영속 저장 방식, 재시도·보존 한도, 재처리 경로, 전달 순서는 제공 문서만으로 확인되지 않는다.
정책 평가용 내부 이벤트 스트림이 있다는 사실도 CORE 전달 보장의 근거가 되지는 않는다.</p>
<p>현행 BCM은 큐 하나로 이를 해결하지 않는다. 상태 변경과 outbox를 함께 기록하고 relay가 Kafka로 발행하며,
CORE의 멱등 반영·처리 완료 확인까지 연결한다. SDK 교체 시 전달 보장은 SDK가 맡고, 원장 반영 완료의 기록·추적은 CORE가 맡는다.</p>
<h4>SDK가 책임질 기능</h4>
<ol>
<li><strong>이벤트 영속 보관.</strong> SDK 상태 변경과 발행할 이벤트 기록을 원자적으로 남긴다. Outbox 또는 동등한 복구 구조를 두어 재시작·프로세스 종료로 알림이 사라지지 않게 한다.</li>
<li><strong>장애 중 적재와 재전송.</strong> CORE 중단·연결 실패·응답 유실에도 미수신 이벤트를 보관하고 같은 eventId로 재전송한다. 재시도 간격·횟수·보존 기간·최대 적재량과 과부하 제어 기준을 정한다.</li>
<li><strong>전달 순서.</strong> 현행 BCM이 보장하는 같은 계정의 발행 순서와 같은 거래의 감지→확정 순서에 대응해야 한다. 감지 누락·역순 입력은 SDK에서 복구·정렬하고 순번과 공백 복구 계약을 제공한다. eventId만으로 순서를 보장한다고 보지 않는다.</li>
<li><strong>최종 실패 격리와 재처리.</strong> 영구 오류와 일시 오류를 구분한다. 재시도 한도를 넘겨도 이벤트를 조용히 버리지 않고 실패 보관함(DLQ 등)에 남겨 경보·조회·수동 재전송을 지원한다.</li>
<li><strong>수신과 업무 완료 구분.</strong> 웹훅 2xx는 CORE 수신 확인이다. SDK는 eventId별 전달 성공·실패를 기록하고 조회할 수 있게 한다. CORE의 원장 반영 완료는 CORE가 자체 추적한다. SDK에 완료를 회신하는 별도 업무 완료 확인 계약은 선택 사항으로 협의한다.</li>
<li><strong>복구와 대사.</strong> 장기 장애 후 누락 이벤트를 범위별로 조회·재전송하고, SDK 발행·전달 이력을 CORE가 자체 처리 이력과 대조할 수 있게 한다. SDK는 미수신 이벤트의 보존·정리 기준을 정하고, 수신 후 미처리 이벤트는 CORE가 보관한다.</li>
</ol>
<h4>CORE에 남는 처리</h4>
<p>CORE는 웹훅 원문을 수신 저장소(Inbox)에 영속 기록한 뒤 2xx를 반환한다. 저장에 실패하면 성공 응답을 보내지 않는다.
원장 반영은 수신 경로와 분리해 비동기로 처리한다. eventId 고유 제약으로 중복을 막고, 원장 반영과 처리 완료 기록을 같은 DB 트랜잭션으로 묶는다.
2xx 이후의 CORE 처리 실패는 CORE의 재처리 대상이다.</p>
<p>CORE는 원장 반영 완료를 자체 기록·추적한다. 미처리·실패 건의 조회·경보·재처리도 CORE가 책임진다.
SDK에 완료를 회신하는 기능은 별도 협의하며, 회신이 없더라도 CORE의 완료 기록과 재처리가 동작해야 한다.
이벤트 순서대로 수신해도 작업자가 병렬 실행하면 적용 순서는 달라질 수 있으므로, CORE도 같은 계정의 처리 순서와 허용 상태 전이를 지켜야 한다.</p>
<h4>요구하는 처리 순서</h4>
<ol>
<li>SDK: 상태 변경과 발행 이벤트를 함께 저장한다.</li>
<li>SDK → CORE: 같은 eventId를 유지해 웹훅을 전달한다.</li>
<li>CORE → SDK: 수신 원문 저장 후 2xx로 수신을 확인한다.</li>
<li>CORE: 저장된 이벤트를 비동기로 처리한다.</li>
<li>CORE는 원장 반영 완료를 자체 기록·추적한다.</li>
</ol>
<p class="requirement-limit"><strong>Queue 제거 판단:</strong> 위 SDK 기능과 CORE 수신·처리 경계를 구현하고,
CORE 중단·SDK 재시작·응답 유실·중복·역순·전달 최종 실패·수신 후 원장 반영 실패와 재처리를 검증한 뒤 결정한다.
현재는 SDK가 이를 이미 제공한다고 단정하거나 Queue 제거를 확정하지 않는다.</p>
<div class="contract-sources">''') + ''.join(link(*source) for source in sources) + '</div></article>'
