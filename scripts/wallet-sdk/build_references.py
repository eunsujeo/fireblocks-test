"""Build the local reference reader from the preserved SDK export.

Requires BeautifulSoup (already used to create the documentation export).
Rendered Mermaid SVGs live in docs/wallet-sdk/_reference/diagrams; the original
30 diagrams are retained as source and need recapturing only when they change.
Run from any directory: python3 scripts/wallet-sdk/build_references.py
"""
from pathlib import Path
from urllib.parse import urlsplit,unquote
import os,json,html,hashlib,zipfile,re
from bs4 import BeautifulSoup

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]/'docs/wallet-sdk'
OUT=ROOT/'_reference';OUT.mkdir(exist_ok=True)
pages=json.loads((ROOT/'api/docs/pages.json').read_text())['pages']
manifest=json.loads((ROOT/'readable-manifest.json').read_text())
slug=lambda route: route.strip('/').replace('/','--') or 'overview'
old_path=lambda route: ROOT/('original-overview.html' if route=='/' else route.strip('/')+'/index.html')
by_path={old_path(p['route']).resolve():p for p in pages}
by_path[(ROOT/'index.html').resolve()]=next(p for p in pages if p['route']=='/')
for p in pages:by_path[(OUT/(slug(p['route'])+'.html')).resolve()]=p
base='/wallet-isms-p/wallet-sdk-docs'
def relative(p,origin):return os.path.relpath(p,origin.parent)
def reference(raw,origin):
 u=urlsplit(raw)
 if u.scheme and not (u.netloc=='static.pg166.io' and u.path.startswith(base)):return None
 path=unquote(u.path)
 if path.startswith(base):target=ROOT/path[len(base):].strip('/') if path[len(base):].strip('/') else ROOT/'index.html'
 elif path.startswith('/'):target=ROOT/path.lstrip('/')
 else:target=origin.parent/path if path else origin
 target=target.resolve()
 if target.is_dir():target=target/'index.html'
 if target.suffix=='.md':target=(ROOT/'index.html') if target.name=='index.md' else target.with_suffix('')/'index.html'
 p=by_path.get(target)
 if not p:return None
 return p,OUT/(slug(p['route'])+'.html'),('#'+u.fragment if u.fragment else '')
def link(a,origin,destination):
 ref=reference(a.get('href',''),origin)
 if ref:
  p,target,fragment=ref;a['href']=relative(target,destination)+fragment;a['data-reference-title']=p['title'];a.attrs.pop('target',None);a.attrs.pop('rel',None);return True
 return False

css=(HERE/'references.css').read_text();(ROOT/'_guide/references.css').write_text(css)
controller=(HERE/'reference-controller.js').read_text();page_script=(HERE/'reference-page.js').read_text()
converted=[]
for p in pages:
 source=old_path(p['route']);destination=OUT/(slug(p['route'])+'.html')
 doc=BeautifulSoup(source.read_text(),'html.parser');article=doc.select_one('main article');assert article,p['route']
 # Interactive API calls are intentionally absent from this reference reader.
 for x in article.select('blume-playground'):x.decompose()
 for x in list(article.select('p')):
  if x.get_text(' ',strip=True).startswith('읽는 사람:'):x.decompose()
 for tabs in article.select('blume-panel-tabs'):
  labels={x.get('id'):x.get_text(' ',strip=True) for x in tabs.select('[role=tab]')};group=doc.new_tag('div')
  for i,panel in enumerate(tabs.select('[role=tabpanel]')):
   details=doc.new_tag('details');summary=doc.new_tag('summary');summary.string=labels.get(panel.get('aria-labelledby'),'예시');details.append(summary)
   if i==0:details['open']=''
   panel.attrs.pop('hidden',None);panel.attrs.pop('role',None);panel.attrs.pop('aria-labelledby',None);details.append(panel.extract());group.append(details)
  tabs.replace_with(group)
 for i,diagram in enumerate(article.select('blume-mermaid')):
  asset=OUT/'diagrams'/f'{slug(p["route"])}-{i}.svg';assert asset.is_file(),asset
  figure=doc.new_tag('figure',attrs={'class':'rendered-diagram'});a=doc.new_tag('a',href=relative(asset,destination),target='_blank',rel='noopener');img=doc.new_tag('img',src=relative(asset,destination),alt=p['title']+' 다이어그램 '+str(i+1));a['data-reference-asset']='';a.append(img);figure.append(a);cap=doc.new_tag('figcaption');cap.string='그림을 누르면 크게 볼 수 있습니다.';figure.append(cap);diagram.replace_with(figure)
 for frame in list(article.select('iframe')):
  src=(source.parent/frame.get('src','')).resolve();figure=frame.find_parent('figure')
  if not figure:continue
  fresh=doc.new_tag('figure');box=doc.new_tag('div',attrs={'class':'interactive-diagram'});f=doc.new_tag('iframe',src=relative(src,destination),title=frame.get('title','다이어그램'),loading='lazy');box.append(f);fresh.append(box);cap=doc.new_tag('figcaption');a=doc.new_tag('a',href=relative(src,destination),target='_blank',rel='noopener');a['data-reference-asset']='';a.string='다이어그램 크게 보기';cap.append(a);fresh.append(cap);figure.replace_with(fresh)
 for pre in article.select('pre'):
  text=pre.get_text();pre.clear();code=doc.new_tag('code');code.string=text;pre.append(code)
 for x in article.select('script,style,button,input,textarea,select,form'):x.decompose()
 for a in list(article.select('a[href]')):
  raw=a['href']
  if a.has_attr('data-reference-asset'):continue
  if link(a,source,destination):continue
  u=urlsplit(raw)
  if u.scheme and u.scheme not in ('https','http','mailto'):
   a.name='span';a.attrs={};continue
  if not u.scheme and not raw.startswith('//'):
   target=(source.parent/unquote(u.path)).resolve() if not u.path.startswith('/') else ROOT/unquote(u.path).lstrip('/')
   if target.is_file() and target.suffix not in ('.html','.md'):a['href']=relative(target,destination)+(('#'+u.fragment) if u.fragment else '');a['target']='_blank';a['rel']='noopener'
   elif target.is_file() and 'diagrams' in target.parts:a['href']=relative(target,destination);a['target']='_blank';a['rel']='noopener'
   else:a.name='span';a.attrs={'class':'unavailable','title':'현재 문서 묶음에 없는 참조입니다.'}
  elif u.netloc=='static.pg166.io' and u.path.startswith(base):a.name='span';a.attrs={'class':'unavailable','title':'현재 문서 묶음에 없는 참조입니다.'}
  else:a['target']='_blank';a['rel']='noopener'
 for img in article.select('img[src]'):
  if img.find_parent(class_='rendered-diagram'):continue
  u=urlsplit(img['src'])
  if not u.scheme:img['src']=relative((source.parent/unquote(u.path)).resolve(),destination)
 for heading in article.select('[role=heading]'):
  heading.name='h'+heading.get('aria-level','2');heading.attrs.pop('role',None);heading.attrs.pop('aria-level',None)
 for x in [article,*article.find_all()]:
  classes=x.get('class',[]);semantic=[c for c in classes if c in ('rendered-diagram','interactive-diagram','unavailable')]
  if 'py-3' in classes and 'border-t' in classes:semantic.append('field')
  if 'items-baseline' in classes:semantic.append('field-signature')
  if 'text-muted-foreground' in classes:semantic.append('ref-meta')
  if any(c.startswith('text-red-') for c in classes):semantic.append('ref-required')
  if 'border' in classes and 'px-4' in classes:semantic.append('schema')
  if 'mb-6' in classes and 'flex-wrap' in classes and x.code:semantic.append('ref-endpoint')
  for k in list(x.attrs):
   if k in ('class','style','hidden') or k.startswith('on') or (k.startswith('data-') and k!='data-reference-title'):del x.attrs[k]
  if semantic:x['class']=semantic
 for table in article.select('table'):
  wrap=doc.new_tag('div',attrs={'class':'table-scroll','tabindex':'0'});table.wrap(wrap)
 content=str(article)
 if p['route'].startswith('/reference/'):
  note=''
 else:note=''
 result=f'''<!doctype html><html lang="ko" class="reference-page"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>{html.escape(p['title'])} · Wallet SDK</title><link rel="stylesheet" href="../_offline/fonts.css"><link rel="stylesheet" href="../_guide/style.css"><link rel="stylesheet" href="../_guide/references.css"><script>if(new URLSearchParams(location.search).get('view')==='modal'&&parent!==window)document.documentElement.classList.add('embed');</script></head><body class="reference-page"><header><a class="brand" href="../index.html">Wallet SDK</a><a class="reference-home" href="../_guide/originals.html">참고 문서 목록</a></header><main><p class="reference-caption">참고 문서</p>{content}{note}<p class="ref-source-footer">2026-09-10 공개 문서 기준 · <a href="../_guide/originals.html#about">문서 출처와 검토 범위</a></p></main><script>{page_script}</script></body></html>'''
 destination.write_text(result);converted.append({'route':p['route'],'path':str(destination.relative_to(ROOT)),'title':p['title']})

for page in manifest['editorialPages']:
 f=ROOT/page['path'];doc=BeautifulSoup(f.read_text(),'html.parser');root=relative(ROOT/'index.html',f).removesuffix('index.html')
 for a in doc.select('a.source'):
  assert link(a,f,f),a.get('href');a['aria-haspopup']='dialog';a['target']='_blank';a['rel']='noopener'
 for old in doc.select('#reference-dialog,#reference-controller,link[data-reference-style]'):old.decompose()
 sheet=doc.new_tag('link',rel='stylesheet',href=root+'_guide/references.css',attrs={'data-reference-style':''});doc.head.append(sheet)
 markup='''<dialog id="reference-dialog" aria-labelledby="reference-title"><div class="reference-toolbar"><button type="button" data-reference-back disabled aria-label="이전 참고 문서">이전</button><h2 id="reference-title">참고 문서</h2><a data-reference-window target="_blank" rel="noopener">새 창으로 보기</a><button type="button" data-reference-close aria-label="참고 문서 닫기">닫기</button></div><p class="reference-loading" role="status">문서를 불러오는 중입니다.</p><iframe id="reference-frame" title="참고 문서"></iframe></dialog>'''
 doc.body.append(BeautifulSoup(markup,'html.parser'))
 js=doc.new_tag('script',id='reference-controller',attrs={'data-root':root});js.string=controller;doc.body.append(js)
 for script in doc.select('script:not(#reference-controller)'):
  if script.string and 'small.textContent=x.kind' in script.string and 'if(x.reference)' not in script.string:
   script.string.replace_with(str(script.string).replace('a.href=new URL(x.path,root).href;',"a.href=new URL(x.path,root).href;if(x.reference){a.dataset.referenceTitle=x.title;a.setAttribute('aria-haspopup','dialog');a.target='_blank';a.rel='noopener'}"))
 f.write_text(str(doc))
search=ROOT/'_guide/search.html';doc=BeautifulSoup(search.read_text(),'html.parser');data=doc.select_one('#data');records=json.loads(data.string)
for x in records:
 ref=reference(x['path'],ROOT/'index.html')
 if x['kind'].startswith(('원문','참고 문서')) and ref:
  p,dest,_=ref;x['path']=str(dest.relative_to(ROOT));x['kind']='참고 문서';x['reference']=True
payload=json.dumps(records,ensure_ascii=False).replace('<','\\u003c');data.string=payload;search.write_text(str(doc))
manifest['referencePages']=converted;manifest['uiRevision']='2026-09-10-reference-reader';(ROOT/'readable-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
readme=ROOT/'README.txt';text=readme.read_text();line='참고 링크는 새로 구성한 참고 문서를 모달로 엽니다. 모달 안의 새 창으로 보기로 따로 열 수 있습니다.\n'
if line not in text:text+='\n'+line
readme.write_text(text)
export=json.loads((ROOT/'export-manifest.json').read_text());export['referencePages']=len(converted);export['files']={}
for f in sorted(ROOT.rglob('*')):
 if f.is_file() and f.name!='export-manifest.json':export['files'][str(f.relative_to(ROOT))]={'bytes':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()}
(ROOT/'export-manifest.json').write_text(json.dumps(export,ensure_ascii=False,indent=2))
with zipfile.ZipFile(ROOT.with_suffix('.zip'),'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for f in sorted(ROOT.rglob('*')):
  if f.is_file():z.write(f,ROOT.name+'/'+str(f.relative_to(ROOT)))
print(json.dumps({'referencePages':len(converted),'guides':len(manifest['editorialPages']),'zip':str(ROOT.with_suffix('.zip'))},ensure_ascii=False))
