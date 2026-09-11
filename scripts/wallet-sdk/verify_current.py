"""Verify the reviewed v1.1.0 update against captured source hashes and reader output."""
from pathlib import Path
import hashlib
import json
import re
from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parents[2] / 'docs/wallet-sdk'
report = json.loads((ROOT / 'source-update.json').read_text())
manifest = json.loads((ROOT / 'readable-manifest.json').read_text())
pages = json.loads((ROOT / 'api/docs/pages.json').read_text())['pages']
assert len(pages) == manifest['originalPages'] == report['currentPages']
assert not any(p['route'].startswith('/v0/') for p in pages)
examples = paragraphs = 0
for page in pages:
    route = page['route']
    md = ROOT / ('index.md' if route == '/' else route.strip('/') + '.md')
    assert hashlib.sha256(md.read_bytes()).hexdigest() == report['sources'][route]['markdownSha256'], route
    detail = ROOT / ('api/docs/pages/' + ('index' if route == '/' else route.strip('/')) + '.json')
    assert json.loads(detail.read_text())['markdown'] == md.read_text(), route
    original = ROOT / ('original-overview.html' if route == '/' else route.strip('/') + '/index.html')
    source = BeautifulSoup(original.read_text(), 'html.parser').select_one('main article')
    reference = ROOT / ('_reference/' + (route.strip('/').replace('/', '--') or 'overview') + '.html')
    reader = BeautifulSoup(reference.read_text(), 'html.parser').select_one('article')
    for block in source.select('blume-playground'): block.decompose()
    assert [p.get_text() for p in source.select('pre')] == [p.get_text() for p in reader.select('pre')], route + ': code examples'
    source_paragraphs = [p.get_text(' ', strip=True) for p in source.select('p')
                         if not p.get_text(' ', strip=True).startswith('읽는 사람:')]
    reader_text = reader.get_text(' ', strip=True)
    for text in source_paragraphs:
        assert text in reader_text, route + ': paragraph ' + text[:80]
    examples += len(source.select('pre')); paragraphs += len(source_paragraphs)
# Cross-format agreement for the contract that changed in this release.
vault = (ROOT / 'fund-flows/vault-transfer.md').read_text()
for text in ('정산 중으로 기록', '목적지 vault 잔액 반영', '출발 vault 잔액 반영 · 완료'):
    assert text in vault
    svg = BeautifulSoup((ROOT / '_reference/diagrams/fund-flows--vault-transfer-0.svg').read_text(), 'html.parser')
    assert text in svg.get_text(' ', strip=True), text
spec = (ROOT / 'openapi/openapi.WalletApi.yaml').read_text()
assert 'SETTLING' in spec and '두 vault의 잔액 반영' in spec
for name in ('transfer', 'recovery', 'status'):
    text = BeautifulSoup((ROOT / ('_guide/' + name + '.html')).read_text(), 'html.parser').select_one('main').get_text(' ', strip=True)
    assert 'SETTLING' in text, name
for name in ('deposit', 'withdrawal', 'policy', 'status'):
    text = BeautifulSoup((ROOT / ('_guide/' + name + '.html')).read_text(), 'html.parser').select_one('main').get_text(' ', strip=True)
    assert '자동 승인' in text and '정책 서버' in text, name
comparison = BeautifulSoup((ROOT.parent / 'flow-comparison/index.html').read_text(), 'html.parser')
assert len(comparison.select('template .sequence-support-note')) == 5
assert 'SETTLING' in comparison.select_one('#contracts').get_text()
assert 'SETTLING' not in (ROOT / 'fund-flows/sweep.md').read_text()
assert len(re.findall(r'^\s*\w+\s*--?>>\s*[+-]?\w+\s*:', (ROOT / 'fund-flows/sweep.md').read_text(), re.M)) == 30
print(json.dumps(dict(currentPages=len(pages), sourceHashes='matched', codeExamples=examples,
                      paragraphs=paragraphs, vaultDiagram='updated', sweepCalls=30,
                      policyStatusNotes=5), ensure_ascii=False))
