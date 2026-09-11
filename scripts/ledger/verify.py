"""Check local links, evidence, page coverage and source preservation."""
from pathlib import Path
from urllib.parse import urlsplit,unquote
from bs4 import BeautifulSoup
import argparse, hashlib, json, re

parser=argparse.ArgumentParser()
parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2]/'docs/ledger')
args=parser.parse_args();root=args.root.resolve()
manifest=json.loads((root/'manifest.json').read_text())
source=json.loads((Path(__file__).parent/'source.json').read_text())
pdf=root.parent/Path(manifest['pdf']).name
assert hashlib.sha256(pdf.read_bytes()).hexdigest()==manifest['pdfSha256']==source['sha256']
docs={str(p.relative_to(root)):BeautifulSoup(p.read_text(),'html.parser') for p in root.rglob('*.html')}
assert len(docs)==manifest['htmlPages']==94
assert len(manifest['coverage'])==64
assert all(c['guides'] and c['reader'] in docs for c in manifest['coverage'])
links=0
for rel,doc in docs.items():
    ids=[x['id'] for x in doc.select('[id]')]
    assert len(ids)==len(set(ids)),(rel,'duplicate IDs')
    assert doc.select_one('h1') and doc.select_one('main')
    for tag in doc.select('[href],[src]'):
        for attr in ['href','src']:
            if not tag.has_attr(attr):continue
            value=tag[attr];u=urlsplit(value)
            if value in ['', '#','about:blank']:continue
            assert not u.scheme and not u.netloc,(rel,'external URL',value)
            target=(root/rel).parent/unquote(u.path) if u.path else root/rel
            target=target.resolve();assert target.is_file(),(rel,value,'missing')
            if u.fragment and target.suffix=='.html':
                other=docs[str(target.relative_to(root))]
                assert other.find(id=unquote(u.fragment)),(rel,value,'missing anchor')
            links+=1
    assert not doc.select('script[src]'),(rel,'external script')
for rel,digest in manifest['files'].items():assert hashlib.sha256((root/rel).read_bytes()).hexdigest()==digest,rel
# Original text and raster evidence remain available, including slide dividers.
for page in source['pages']:
    n=page['page'];doc=docs[f'_reference/page-{n:02}.html']
    assert doc.select_one('pre.source-text').get_text()==page['text'],n
    assert hashlib.sha256((root/page['image']).read_bytes()).hexdigest()==page['imageSha256'],n
steps=sum(len(doc.select('.step')) for doc in docs.values())
assert steps==manifest['numberedSteps']==291
assert sum(len(doc.select('.source-mark')) for doc in docs.values())==291
flows=[rel for rel in docs if rel.startswith('flows/')]
assert len(flows)==13
# Business distinctions that must survive editorial rewriting.
dep=docs['flows/deposit.html']
assert '온체인잔고: 증가' in dep.select_one('#phase-31 .balance-panel').get_text()
assert '온체인잔고: 증가' not in dep.select_one('#phase-32 .balance-panel').get_text()
assert '입금중: 감소' in dep.select_one('#phase-32 .balance-panel').get_text()
settlement=docs['flows/settlement.html'].get_text()
assert '모든 BCTX' in settlement and '개별 BCTX' in settlement and '전체 완료 조건' in settlement
for n in [14,16]:
    original=source['pages'][n-1]['text'];formula=original[original.index('● 다음'):].replace('Strictly Confidential','').strip()
    assert formula in docs['reconciliation.html'].get_text()
assert 'Type 업데이트' in docs['data.html'].get_text()
assert '항상 80%' in docs['flows/bridging-sweep.html'].get_text()
assert not list(root.rglob('*.js')) and not list(root.rglob('*.command')) and not root.with_suffix('.zip').exists()
report={'htmlPages':len(docs),'links':links,'sourcePages':64,'flows':13,'numberedSteps':steps,'pdfUnchanged':True,'hashesValid':True,'sourceTextPreserved':True,'offlineOnly':True}
print(json.dumps(report,ensure_ascii=False))
