"""Refresh the current public SDK export; retain the existing offline renderer.

Network reads are limited to the documentation site. No SDK APIs are called.
Run build_references.py and render_diagrams.cjs after reviewing the source diff.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path
from urllib.parse import urlsplit, unquote
from urllib.request import urlopen
import argparse
import hashlib
import json
import os
import re
from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parents[2] / 'docs/wallet-sdk'
BASE = 'https://static.pg166.io/wallet-isms-p/wallet-sdk-docs'
PREFIX = urlsplit(BASE).path
ALIASES = {
    '/policy/rule/structure': '/policy/rules',
    '/concepts': '/accounts-wallets',
    **{'/concepts/' + part: '/accounts-wallets/' + part
       for part in ('accounts', 'wallets', 'catalog', 'domain-model')},
    '/concepts/terminology': '/start/terminology',
    '/concepts/compliance': '/start/compliance',
    '/fund-flows/onboarding': '/accounts-wallets/onboarding',
    '/policy/authoring/workflow': '/policy/workflow',
    '/policy/ops/operations': '/policy/operations',
}


def fetch(url):
    assert url.startswith(BASE + '/') or url == BASE, url
    with urlopen(url, timeout=60) as response:
        return response.read()


def raw_path(route):
    return ROOT / ('original-overview.html' if route == '/' else route.strip('/') + '/index.html')


def md_path(route):
    return ROOT / ('index.md' if route == '/' else route.strip('/') + '.md')


def slug(route):
    return route.strip('/').replace('/', '--') or 'index'


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data if isinstance(data, bytes) else data.encode())


def dump(data):
    return json.dumps(data, ensure_ascii=False, indent=2)


def finish_source_links(pages):
    """Keep published text, remap moved routes, mark omitted/private links unavailable."""
    routes = {p['route'] for p in pages}
    for p in pages:
        file = raw_path(p['route']); doc = BeautifulSoup(file.read_text(), 'html.parser')
        for a in doc.select('a[href]'):
            u = urlsplit(a['href'])
            if u.scheme or not u.path: continue
            target = (file.parent / unquote(u.path)).resolve()
            if not target.is_relative_to(ROOT): continue
            route = '/' + str(target.relative_to(ROOT)).removesuffix('/index.html')
            if route == '/changelog':
                a['href'] = os.path.relpath(ROOT / '_guide/originals.html', file.parent) + '#group-8'
            elif route in ALIASES and ALIASES[route] in routes:
                a['href'] = os.path.relpath(raw_path(ALIASES[route]), file.parent)
            elif not target.is_file():
                a.name = 'span'; a.attrs.pop('href', None)
                a['title'] = '현재 문서 묶음에 없는 참조입니다.'
        write(file, str(doc))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache', type=Path, required=True)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    listing = json.loads(fetch(BASE + '/api/docs/pages.json'))
    pages = [p for p in listing['pages'] if not p['route'].startswith('/v0/') and p['route'] != '/v0']
    listing.update(pages=pages, count=len(pages))
    versions = [p['route'].split('/')[-1][1:].replace('-', '.') for p in pages if re.fullmatch(r'/changelog/v[0-9]+-[0-9]+-[0-9]+', p['route'])]
    version = max(versions, key=lambda v: tuple(map(int, v.split('.'))))
    old_pages = json.loads((ROOT / 'api/docs/pages.json').read_text())['pages']
    routes = {p['route'] for p in pages}
    old_routes = {p['route'] for p in old_pages}
    changed, diagram_changes, source_hashes = [], [], {}

    def capture(p):
        name = slug(p['route'])
        json_file, html_file = args.cache / (name + '.json'), args.cache / (name + '.html')
        # A cache is a single reviewed snapshot, not a cross-release download cache.
        if not json_file.exists(): write(json_file, fetch(p['json']))
        if not html_file.exists(): write(html_file, fetch(p['url']))
        return p, json.loads(json_file.read_text()), html_file.read_text()

    captures = list(ThreadPoolExecutor(max_workers=8).map(capture, pages))
    template = BeautifulSoup(raw_path('/fund-flows/vault-transfer').read_text(), 'html.parser')
    bootstrap = next(s.get_text() for s in template.select('script') if 'window.__offlineRoot = new URL(' in s.get_text())
    assets = {}

    def local_link(value, origin):
        u = urlsplit(value)
        if u.scheme and not (u.netloc == 'static.pg166.io' and u.path.startswith(PREFIX)):
            return value
        if not u.path.startswith('/'):
            return value
        rel = unquote(u.path[len(PREFIX):] if u.path.startswith(PREFIX) else u.path)
        route = rel.rstrip('/') or '/'
        route = ALIASES.get(route, route)
        if route in routes:
            target = ROOT / 'index.html' if route == '/' else raw_path(route)
        elif route.endswith('.md') and route[:-3] in ALIASES:
            target = md_path(ALIASES[route[:-3]])
        else:
            target = ROOT / rel.lstrip('/')
        suffix = ('?' + u.query if u.query else '') + ('#' + u.fragment if u.fragment else '')
        return os.path.relpath(target, origin.parent) + suffix

    for p, data, raw in captures:
        route = p['route']; old = md_path(route).read_text() if md_path(route).exists() else ''
        if old != data['markdown']: changed.append(route)
        doc = BeautifulSoup(raw, 'html.parser')
        diagrams = re.findall(r'```mermaid\n(.*?)```', data['markdown'], re.S)
        old_diagrams = re.findall(r'```mermaid\n(.*?)```', old, re.S)
        if diagrams != old_diagrams: diagram_changes.append(route)
        source_hashes[route] = {'markdownSha256': hashlib.sha256(data['markdown'].encode()).hexdigest(),
                                'htmlSha256': hashlib.sha256(raw.encode()).hexdigest()}
        write(md_path(route), data['markdown'])
        write(ROOT / ('api/docs/pages/' + ('index' if route == '/' else route.strip('/')) + '.json'), dump(data))
        # The bundled runtime already provides Mermaid, navigation and API example tabs.
        for script in doc.select('script[src],script[type="module"]'): script.decompose()
        for style in doc.select('style'):
            if '@font-face' in style.get_text(): style.decompose()
        for link in doc.select('link'):
            if 'preload' in link.get('rel', []) or 'modulepreload' in link.get('rel', []): link.decompose()
        fonts = doc.new_tag('link', rel='stylesheet', href=os.path.relpath(ROOT / '_offline/fonts.css', raw_path(route).parent))
        doc.head.append(fonts)
        for node in doc.select('[href],[src]'):
            attr = 'href' if node.has_attr('href') else 'src'; value = node[attr]
            # Canonical attribution remains the source URL; reading links stay local.
            if node.name == 'link' and 'canonical' in node.get('rel', []): continue
            converted = local_link(value, raw_path(route)); node[attr] = converted
            u = urlsplit(value)
            if u.path.startswith(PREFIX + '/'):
                rel = u.path[len(PREFIX) + 1:]
                if not u.query and Path(rel).suffix in ('.css', '.svg', '.png', '.webp', '.woff2'):
                    assets[rel] = BASE + '/' + rel
        script = doc.new_tag('script')
        relative = os.path.relpath(ROOT, raw_path(route).parent) + '/'
        script.string = re.sub(r'new URL\("[^"\n]*", location.href\)', 'new URL(' + json.dumps(relative) + ', location.href)', bootstrap, count=1)
        doc.body.append(script)
        write(raw_path(route), str(doc))
    for rel, url in assets.items():
        if not (ROOT / rel).exists(): write(ROOT / rel, fetch(url))
    finish_source_links(pages)
    # Remove only files owned by the previous export for retired routes.
    for route in old_routes - routes:
        for path in [raw_path(route), md_path(route), ROOT / ('api/docs/pages/' + route.strip('/') + '.json'),
                     ROOT / ('_reference/' + slug(route) + '.html')]:
            if path.exists(): path.unlink()
    write(ROOT / 'api/docs/pages.json', dump(listing))
    nav = json.loads(fetch(BASE + '/api/docs/navigation.json'))
    nav['selectors'] = []
    def prune(nodes):
        result = []
        for node in nodes:
            if node.get('route', '').startswith('/v0') or node.get('path', '').startswith('/v0'): continue
            if 'children' in node: node['children'] = prune(node['children'])
            result.append(node)
        return result
    nav['sidebar'] = prune(nav['sidebar']); write(ROOT / 'api/docs/navigation.json', dump(nav))
    search = json.loads(fetch(BASE + '/blume-search.json'))
    search = [x for x in search if not any('/v0/' in str(x.get(k, '')) for k in ('url', 'route', 'id'))]
    write(ROOT / 'blume-search.json', dump(search))
    for path in sorted((ROOT / 'openapi').glob('*.yaml')): write(path, fetch(BASE + '/openapi/' + path.name))
    write(ROOT / 'agent-readability.json', fetch(BASE + '/agent-readability.json'))
    write(ROOT / 'openapi.json', fetch(BASE + '/openapi.json'))
    # RSS is also a current-version reading surface, not a v0 snapshot archive.
    import xml.etree.ElementTree as ET
    rss = ET.fromstring(fetch(BASE + '/changelog/rss.xml'))
    channel = rss.find('channel')
    for item in list(channel.findall('item')):
        if '/v0/' in item.findtext('link', ''): channel.remove(item)
    write(ROOT / 'changelog/rss.xml', ET.tostring(rss, encoding='utf-8', xml_declaration=True))
    llms = '# Wallet SDK\n\nCurrent public documentation. Historical /v0 snapshots excluded.\n\n'
    llms += '\n'.join('- [' + p['title'] + '](' + p['url'] + ')' for p in pages) + '\n'
    write(ROOT / 'llms.txt', llms)
    write(ROOT / 'llms-full.txt', llms + '\n\n' + '\n\n'.join(data['markdown'] for _, data, _ in captures))
    write(ROOT / 'sitemap.xml', '<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">' + ''.join('<url><loc>' + p['url'] + '</loc></url>' for p in pages) + '</urlset>')
    runtime = ROOT / '_offline/runtime.html'; doc = BeautifulSoup(runtime.read_text(), 'html.parser'); data_node = doc.select_one('#wallet-data')
    original = data_node.get_text()
    old_payload = json.loads(re.search(r'window\.__offlinePayloads = (.*);\nwindow\.__offlineURL', original).group(1))
    payload = {key: (ROOT / key).read_text() for key in old_payload if (ROOT / key).is_file()}
    for p, data, _ in captures:
        payload[str(md_path(p['route']).relative_to(ROOT))] = data['markdown']
        key = 'api/docs/pages/' + ('index' if p['route'] == '/' else p['route'].strip('/')) + '.json'
        payload[key] = (ROOT / key).read_text()
    page_map = {p['route']: str(raw_path(p['route']).relative_to(ROOT)) for p in pages}
    jsdump = lambda value: json.dumps(value, ensure_ascii=False).replace('<', '\\u003c')
    data_node.string = ('// The containing document supplies its relative document root.\nwindow.__offlinePages = ' + jsdump(page_map)
                        + ';\nwindow.__offlinePayloads = ' + jsdump(payload) + ';\nwindow.__offlineURL' + original.split('\nwindow.__offlineURL', 1)[1])
    write(runtime, str(doc))
    manifest = json.loads((ROOT / 'readable-manifest.json').read_text())
    manifest.update(capturedDate=str(date.today()), sourceVersion=version, originalPages=len(pages))
    write(ROOT / 'readable-manifest.json', dump(manifest))
    report = dict(capturedDate=str(date.today()), sourceVersion=version, currentPages=len(pages), changedPages=changed,
                  addedRoutes=sorted(routes-old_routes), removedRoutes=sorted(old_routes-routes),
                  changedDiagrams=diagram_changes, sources=source_hashes)
    write(ROOT / 'source-update.json', dump(report))
    print(dump({k: v for k, v in report.items() if k != 'sources'}))


if __name__ == '__main__': main()
