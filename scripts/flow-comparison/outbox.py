"""Current BCM outbox flow, separate from SDK integration requirements."""
from html import escape


def diagram():
    actors = ['BCM', 'BCM DB', '발송 작업', 'Kafka', 'DAW-CORE']
    xs = [70, 285, 500, 715, 930]
    messages = [
        (0, 1, ['거래 상태 + 보낼 이벤트', '+ 수신 처리 완료를 함께 저장'], False),
        (2, 1, ['발송 대기(P) 이벤트 조회'], False),
        (1, 2, ['이벤트 · eventId'], True),
        (2, 3, ['이벤트 발행'], False),
        (3, 2, ['발행 성공 응답'], True),
        (2, 1, ['발행 성공(S) 기록'], False),
        (3, 4, ['이벤트 전달'], True),
        (4, 4, ['eventId 중복 확인', '원장 + 처리 완료 기록 커밋'], False),
        (4, 0, ['PUT /events/{eventId}/completion'], False),
        (0, 1, ['CORE 처리 완료 별도 기록'], False),
        (0, 4, ['완료 확인 성공 응답'], True),
        (4, 3, ['Kafka 소비 위치(offset) 커밋'], False),
    ]
    height = 110 + len(messages) * 76
    parts = [f'<svg viewBox="0 0 1040 {height}" role="img" aria-labelledby="outbox-svg-title outbox-svg-desc" xmlns="http://www.w3.org/2000/svg">',
             '<title id="outbox-svg-title">BCM Outbox 이벤트 전달과 CORE 처리 완료</title>',
             '<desc id="outbox-svg-desc">BCM이 거래 상태와 이벤트를 함께 저장하고, 발송 작업이 Kafka로 전달한다. CORE는 원장 반영 후 BCM 완료 확인 API의 성공 응답을 받고 소비 위치를 커밋한다.</desc>',
             '<defs><marker id="outbox-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10Z" fill="#247763"/></marker></defs>']
    for actor, x in zip(actors, xs):
        parts.append(f'<rect x="{x-62}" y="12" width="124" height="42" rx="4" fill="#eff6f3" stroke="#c6d9ce"/><text x="{x}" y="39" text-anchor="middle" font-weight="600">{actor}</text><path d="M{x} 54V{height-20}" stroke="#ccd9d1" stroke-dasharray="5 5"/>')
    for i, (a, b, labels, reply) in enumerate(messages):
        y = 124 + i * 76
        start, end = xs[a], xs[b]
        dash = ' stroke-dasharray="6 5"' if reply else ''
        if a == b:
            path = f'M{start} {y-6}h44v24h-44'
            label_x = start - 10
            anchor = 'end'
        else:
            path = f'M{start} {y}H{end}'
            label_x = (start + end) / 2
            anchor = 'middle'
        for j, label in enumerate(labels):
            label_y = y - 14 - (len(labels)-1-j)*20
            text = escape((f'{i+1}. ' if j == 0 else '') + label)
            parts.append(f'<text x="{label_x}" y="{label_y}" text-anchor="{anchor}">{text}</text>')
        parts.append(f'<path d="{path}" fill="none" stroke="#247763" stroke-width="1.7"{dash} marker-end="url(#outbox-arrow)"/>')
    parts.append('</svg>')
    return ''.join(parts)


def detail():
    return '''<article id="bcm-outbox" class="event-requirement outbox-detail" aria-labelledby="outbox-body-title">
<p class="requirement-status">현재 BCM 구현 · CORE 연동 계약</p>
<h3 id="outbox-body-title">이벤트 저장부터 원장 반영까지</h3>
<p class="outbox-key"><strong>Kafka 발행 성공과 CORE 원장 반영 완료는 다르다.</strong><br>
발행 성공은 Outbox에, CORE 처리 완료는 별도 원장에 기록한다.</p>
<p>BCM은 거래 상태 변경과 보낼 이벤트, 수신 처리 완료를 같은 DB 트랜잭션으로 저장한다.
발송 작업은 저장된 이벤트를 Kafka로 전달한다.</p>
<figure class="outbox-figure"><figcaption>정상 처리 순서 · 작은 화면에서는 좌우로 이동해 볼 수 있습니다.</figcaption>
<div class="outbox-diagram" tabindex="0" role="region" aria-label="BCM Outbox 시퀀스 다이어그램">''' + diagram() + '''</div></figure>
<h4>실패하면 어떻게 되나</h4>
<ul>
<li><strong>DB 저장 후 BCM이 중단되면:</strong> 저장된 대기 이벤트를 발송 작업이 이어서 전송한다.</li>
<li><strong>Kafka 발행에 실패하면:</strong> 재시도한다. 한도를 넘으면 실패 상태로 격리하고 알림을 발생시킨다.</li>
<li><strong>같은 이벤트가 다시 전달되면:</strong> CORE는 eventId로 중복을 확인해 원장을 두 번 반영하지 않는다. Kafka 발행 후 BCM의 성공 기록이 실패한 경우에도 재전달될 수 있다.</li>
<li><strong>원장 반영 후 완료 확인이 실패하면:</strong> CORE는 소비 위치를 커밋하지 않는다. 재전달 시 원장 반영은 건너뛰고 완료 확인을 다시 시도한다.</li>
</ul>
<h4>두 완료 기록을 구분한다</h4>
<p><strong>Outbox:</strong> P(대기) → D(발송 시도) → S(Kafka 발행 성공). 발행 실패 시 P로 돌아가 재시도하고, 한도 초과 시 F(실패 격리)로 남긴다.</p>
<p><strong>CORE 완료 확인:</strong> 현행 계약은 원장 반영 커밋 → <code>PUT /events/{eventId}/completion</code> 성공 → Kafka 소비 위치 커밋 순서다. 필수 소비자의 완료 확인이 없는 이벤트는 Outbox 정리 대상에서 제외한다.</p>
<p class="requirement-limit">이 그림은 현재 BCM의 흐름이다. CORE 부분은 연동 계약을 나타낸다.
SDK 교체안에서는 CORE가 원장 반영 완료를 자체 기록·추적하며, SDK로 완료를 회신하는 기능은 선택 협의 사항이다.</p>
<p class="outbox-sources">근거 · <a href="../design/02-bcm-flow.md" target="_blank" rel="noopener">BCM 이벤트·완료 확인 계약 ↗</a> · <a href="../api/api.html" target="_blank" rel="noopener">BCM API ↗</a></p>
</article>
<dialog id="outbox-dialog" aria-labelledby="outbox-dialog-title">
<header class="event-details-toolbar"><h2 id="outbox-dialog-title">BCM Outbox 흐름</h2><button type="button" data-outbox-close aria-label="BCM Outbox 상세 닫기">닫기 ×</button></header>
<div id="outbox-content"></div></dialog>'''
