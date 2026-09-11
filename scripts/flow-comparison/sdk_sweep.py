"""Render every message and note from the archived SDK sweep diagrams."""
from html import escape
from pathlib import Path
import re
import textwrap

SOURCE = Path(__file__).resolve().parents[2] / 'docs/wallet-sdk/fund-flows/sweep.md'


def plain(value):
    return re.sub(r'<br\s*/?>', '\n', value).strip()


def load_sweep():
    source = SOURCE.read_text()
    blocks = re.findall(r'```mermaid\n(.*?)```', source, re.S)
    assert len(blocks) == 2, 'Review the SDK sweep source structure before rebuilding'
    actors, entries, messages = {}, [], []
    titles = ['요청 접수 · 정책 검사 · 서명 허가 발급', '서명 직전 확인 · 전파 · 집금 결과 반영']
    for title, block in zip(titles, blocks):
        entries.append(dict(kind='section', label=title))
        for raw in block.splitlines():
            line = raw.strip()
            if not line or line in ('sequenceDiagram', 'autonumber'):
                continue
            participant = re.fullmatch(r'participant (\w+) as (.+)', line)
            if participant:
                key, label = participant.groups()
                label = {'PT': 'DAW-CORE', 'SYS': 'WALLET-SDK', 'OC': '블록체인'}.get(key, label)
                if key in actors:
                    assert actors[key] == label
                actors[key] = label
                continue
            message = re.fullmatch(r'(\w+)\s*(--?>>)\s*([+-]?)(\w+)\s*:\s*(.+)', line)
            if message:
                start, arrow, activation, end, label = message.groups()
                entry = dict(kind='message', start=start, end=end, arrow=arrow,
                             activation=activation, label=plain(label))
                entries.append(entry)
                messages.append((actors[start], actors[end], plain(label), 'reply' if arrow=='-->>' else 'call'))
                continue
            note = re.fullmatch(r'Note (over|right of) ([\w, ]+)\s*:\s*(.+)', line)
            if note:
                where, keys, label = note.groups()
                entries.append(dict(kind='note', where=where, actors=keys.replace(' ', '').split(','), label=plain(label)))
                continue
            raise ValueError('Unsupported SDK diagram syntax: '+line)
    return dict(
        detailed=True, review=False, actors=actors, entries=entries, messages=messages,
        min_width=1980,
        note='SDK 집금 원문의 두 도식을 순서대로 연결했다. 파트너사는 DAW-CORE로 표시했다. '
             '입금 1건을 지정하며 여러 입금의 온체인 배치 계약은 확인되지 않는다. '
             '원문의 POST /sweeps·OK(sweepId)는 OpenAPI에서 POST /api/v1/sweeps·201로 정의한다. '
             'depositId·destinationVaultId·referenceId와 Idempotency-Key가 필요하다. '
             '서명 인가는 유효기간이 있는 일회용 서명 허가다. '
             '해제 CREDIT과 이동 DEBIT은 동결 해제와 입금 지갑에서의 이동을 함께 기록한다는 뜻이다.'
    )


def wrapped(value, width):
    return [line for paragraph in value.splitlines()
            for line in (textwrap.wrap(paragraph, width, break_long_words=False, break_on_hyphens=False) or [''])]


def detailed_svg(sequence, ident):
    actors = sequence['actors']
    width = sequence['min_width']
    xs = {key: 90 + index*(width-180)/(len(actors)-1) for index, key in enumerate(actors)}
    y, number, active = 104, 0, {}
    placed, activations = [], []
    for entry in sequence['entries']:
        item = dict(entry, y=y)
        if entry['kind']=='message':
            number += 1
            a, b = entry['start'], entry['end']
            span = abs(xs[a]-xs[b]) if a!=b else 210
            item['lines'] = wrapped(entry['label'], max(16, int((span-24)/10)))
            item['number'] = number
            item['arrow_y'] = y+len(item['lines'])*19+10
            item['height'] = len(item['lines'])*19+62
            if entry['activation']=='+':
                assert b not in active
                active[b] = item['arrow_y']
            elif entry['activation']=='-':
                assert a in active
                activations.append((a, active.pop(a), item['arrow_y']))
        elif entry['kind']=='note':
            start = min(xs[key] for key in entry['actors'])
            end = max(xs[key] for key in entry['actors'])
            box_width = max(320, end-start+140)
            x = start+20 if entry['where']=='right of' else (start+end-box_width)/2
            item['x'] = max(20, min(x, width-box_width-20))
            item['width'] = box_width
            item['lines'] = wrapped(entry['label'], int((box_width-32)/12))
            item['height'] = len(item['lines'])*19+38
        else:
            item['height'] = 60
        placed.append(item)
        y += item['height']
    assert not active, 'Unclosed SDK activation'
    height = y+24
    esc = escape
    output = [f'<svg class="sdk-detail" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" role="img" aria-labelledby="{ident}-title {ident}-desc">',
              f'<title id="{ident}-title">SDK 집금 전체 시퀀스</title>',
              f'<desc id="{ident}-desc">'+esc(' / '.join(f'{a} → {b}: {label}' for a,b,label,_ in sequence['messages']))+'</desc>',
              f'<defs><marker id="{ident}-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="#345d91"/></marker></defs>']
    for key, label in actors.items():
        x = xs[key]
        output.append(f'<g data-actor="{key}"><line x1="{x}" x2="{x}" y1="62" y2="{height-15}" stroke="#c6d2cc" stroke-dasharray="5 5"/><rect x="{x-75}" y="18" width="150" height="42" rx="4" fill="#edf2f9" stroke="#d4dfd8"/><text x="{x}" y="44" text-anchor="middle" fill="#345d91" font-size="13" font-weight="600">{esc(label)}</text></g>')
    for key, start, end in activations:
        output.append(f'<rect class="activation" x="{xs[key]-5}" y="{start}" width="10" height="{end-start}" fill="#edf2f9" stroke="#a3b6d0"/>')
    for item in placed:
        y = item['y']
        if item['kind']=='section':
            output.append(f'<rect x="20" y="{y-8}" width="{width-40}" height="36" fill="#edf2f9"/><text x="38" y="{y+16}" font-size="14" fill="#345d91" font-weight="600">{esc(item["label"])}</text>')
        elif item['kind']=='note':
            x = item['x']
            output.append(f'<g class="source-note" data-source-label="{esc(item["label"], quote=True)}"><rect x="{x}" y="{y-8}" width="{item["width"]}" height="{item["height"]-12}" rx="4" fill="#fcf8ed" stroke="#dfc99c"/>')
            for index, line in enumerate(item['lines']):
                output.append(f'<text x="{x+16}" y="{y+12+index*19}" font-size="12" fill="#765718">{esc(line)}</text>')
            output.append('</g>')
        else:
            a, b = item['start'], item['end']
            x1, x2, arrow_y = xs[a], xs[b], item['arrow_y']
            output.append(f'<g class="source-message" data-from="{a}" data-to="{b}" data-arrow="{item["arrow"]}" data-source-label="{esc(item["label"], quote=True)}">')
            dash = ' stroke-dasharray="6 4"' if item['arrow']=='-->>' else ''
            if a==b:
                direction = -1 if x1>width-300 else 1
                bend = x1+direction*36
                output.append(f'<path d="M {x1} {arrow_y} H {bend} V {arrow_y+20} H {x1}" fill="none" stroke="#345d91" stroke-width="1.6"{dash} marker-end="url(#{ident}-arrow)"/>')
                tx, anchor = (x1-12, 'end') if direction==-1 else (x1+12, 'start')
            else:
                output.append(f'<line x1="{x1}" x2="{x2}" y1="{arrow_y}" y2="{arrow_y}" stroke="#345d91" stroke-width="1.6"{dash} marker-end="url(#{ident}-arrow)"/>')
                tx, anchor = (x1+x2)/2, 'middle'
            for index, line in enumerate(item['lines']):
                prefix = str(item['number'])+'. ' if index==0 else ''
                output.append(f'<text x="{tx}" y="{y+index*19}" text-anchor="{anchor}" font-size="14" fill="#345d91" paint-order="stroke" stroke="white" stroke-width="4">{esc(prefix+line)}</text>')
            output.append('</g>')
    return ''.join(output)+'</svg>'
