"""Sources for each displayed stage; indices follow the four steps per flow."""


def stage_references(daw, sdk):
    deposit = sdk('fund-flows--deposit', 'SDK 입금')
    webhook = sdk('fund-flows--webhooks', 'SDK 통지')
    release = sdk('fund-flows--release', 'SDK 동결·해제')
    sweep = sdk('fund-flows--sweep', 'SDK 집금')
    sweep_api = sdk('reference--wallet--sweeps--sweeps-create', 'SDK Sweep API')
    withdrawal = sdk('fund-flows--withdrawal', 'SDK 출금·반환')
    policy_results = sdk('policy--results', 'SDK 정책 결과·현재 구현')
    movements = sdk('fund-flows--movements', 'SDK 이동 경로')
    return {
        'normal': [
            [daw(31, '입금 감지'), deposit],
            [daw(31, '입금 감지'), deposit, webhook],
            [daw(32, '입금 확정'), webhook, release],
            [daw(46, '자동스윕 · 받는주소 → 보내는주소'), ('../api/api.html', 'BCM Sweep API'), ('../api/openapi.yaml', 'BCM OpenAPI · YAML'), ('../design/06-sweep.md', 'BCM 배치 실행 · Markdown'), sweep, sweep_api, webhook, release, policy_results],
        ],
        'suspense': [
            [daw(57, '고객가수금 분류'), deposit, release],
            [daw(58, '가수금 입금 확정'), webhook, release],
            [daw(57, '후속 확인'), daw(59, '가수금 이동'), webhook],
            [daw(59, '고객 자산 편입'), release, sweep],
        ],
        'sweep': [
            [daw(57, '가수금 집금'), sweep, sweep_api, policy_results],
            [daw(46, '자동스윕 개요'), daw(57, '가수금 집금'), sweep, webhook, policy_results],
            [daw(57, '집금 후 별단입금 보관'), sweep, release, webhook],
            [daw(46, '스윕 개요 · 실패 상세 없음'), daw(57, '가수금 개요'), sweep, webhook],
        ],
        'return-before': [
            [daw(57, '오입금 반환 원칙'), withdrawal],
            [daw(60, '보내는주소 반환 · 직접 반환과 다름'), withdrawal, policy_results],
            [daw(60, '반환 출발지'), withdrawal, movements],
            [daw(61, '보내는주소 반환 원장'), withdrawal],
        ],
        'return-after': [
            [daw(57, '집금 후 보관'), sweep],
            [daw(60, '오입금 송금 요청'), withdrawal, movements],
            [daw(60, '오입금 송금 원장·전파'), withdrawal],
            [daw(61, '오입금 송금 확정'), withdrawal, webhook],
        ],
        'company': [
            [daw(62, '회사 가수금 개요'), release, sdk('accounts-wallets--wallets', 'SDK 지갑 용도')],
            [daw(63, '회사 귀속 확인'), sdk('accounts-wallets--domain-model', 'SDK 귀속 모델')],
            [daw(63, '회사 원장 편입'), release],
            [daw(63, '회사 편입 · 독립 해제 단계 없음'), release, policy_results],
        ],
    }
