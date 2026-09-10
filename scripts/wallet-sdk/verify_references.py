"""Check the final ZIP and write its fresh extraction path for browser checks."""
from pathlib import Path
from urllib.parse import urlsplit,unquote
import tempfile,zipfile,json,hashlib,os
from bs4 import BeautifulSoup
ROOT=Path(__file__).resolve().parents[2]/'docs/wallet-sdk'
archive=ROOT.with_suffix('.zip');temp=Path(tempfile.mkdtemp(prefix='wallet-reference-zip-'))
with zipfile.ZipFile(archive) as z:z.extractall(temp)
r=(temp/ROOT.name).resolve();m=json.loads((r/'readable-manifest.json').read_text());cache={};issues=[];links=0
all_pages=[*m['editorialPages'],*m['referencePages']]
for p in all_pages:cache[(r/p['path']).resolve()]=BeautifulSoup((r/p['path']).read_text(),'html.parser')
for p in all_pages:
 file=r/p['path'];doc=cache[file.resolve()]
 for a in doc.select('[href],[src]'):
  value=a.get('href') or a.get('src')
  if not value:continue
  u=urlsplit(value)
  if u.scheme or value.startswith('//'):
   if u.netloc=='static.pg166.io' and '/wallet-sdk-docs' in u.path:issues.append([p['path'],'source-site link',value])
   continue
  target=(file.parent/unquote(u.path)).resolve() if u.path else file.resolve();links+=1
  if not target.is_file():issues.append([p['path'],'missing target',value]);continue
  if a.has_attr('data-reference-title') and target.parent!=r/'_reference':issues.append([p['path'],'reference not rebuilt',value])
  if target in cache and u.fragment and not cache[target].find(id=unquote(u.fragment)):issues.append([p['path'],'missing anchor',value])
 for x in doc.select('script[src]'):issues.append([p['path'],'external script',x['src']])
 if p in m['referencePages']:
  if doc.select('blume-playground,blume-panel-tabs,blume-mermaid,form'):issues.append([p['path'],'unconverted component'])
export=json.loads((r/'export-manifest.json').read_text())
for f,v in export['files'].items():assert hashlib.sha256((r/f).read_bytes()).hexdigest()==v['sha256'],f
for p in r.rglob('*'):
 if p.suffix in ('.js','.mjs','.command','.py'):issues.append(['export contains executable file',str(p)])
summary={'archive':str(archive),'root':str(r),'guidePages':len(m['editorialPages']),'referencePages':len(m['referencePages']),'linksChecked':links,'issues':issues}
Path('/tmp/wallet-reference-test.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2));print(json.dumps(summary,ensure_ascii=False));assert not issues
