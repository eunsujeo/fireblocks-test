"""Extract local PDF evidence. Uses installed Poppler and Pillow; no network calls."""
from pathlib import Path
from tempfile import TemporaryDirectory
import hashlib, json, re, subprocess, xml.etree.ElementTree as ET
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
PDF = ROOT / 'docs/원장_v0.1.4.pdf'
OUT = ROOT / 'docs/ledger/assets'

def run(args):
    result = subprocess.run(args, capture_output=True, text=True, check=True)
    return result.stderr

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix='ledger-extract-') as temp:
        temp = Path(temp)
        warnings = run(['pdftotext', '-raw', str(PDF), str(temp / 'raw.txt')])
        run(['pdftotext', '-bbox-layout', str(PDF), str(temp / 'bbox.xml')])
        run(['pdftoppm', '-scale-to', '2160', '-png', str(PDF), str(temp / 'page')])
        raw = (temp / 'raw.txt').read_text().split('\f')
        ns = {'x': 'http://www.w3.org/1999/xhtml'}
        xml = ET.parse(temp / 'bbox.xml').findall('.//x:page', ns)
        pages = []
        for n, page in enumerate(xml, 1):
            blocks = []
            for block in page.findall('.//x:block', ns):
                text = '\n'.join(' '.join(w.text or '' for w in line.findall('x:word', ns)) for line in block.findall('x:line', ns))
                blocks.append({'text': text, 'box': [float(block.attrib[k]) for k in ['xMin', 'yMin', 'xMax', 'yMax']]})
            image = OUT / f'page-{n:02}.webp'
            Image.open(temp / f'page-{n:02}.png').convert('RGB').save(image, 'WEBP', lossless=True, method=6)
            pages.append({'page': n, 'text': raw[n-1].strip(), 'blocks': blocks, 'image': f'assets/{image.name}', 'imageSha256': hashlib.sha256(image.read_bytes()).hexdigest()})
        result = {'pdf': 'docs/원장_v0.1.4.pdf', 'sha256': hashlib.sha256(PDF.read_bytes()).hexdigest(), 'pageCount': len(pages), 'extractionWarnings': sorted(set(warnings.splitlines())), 'pages': pages}
        (HERE / 'source.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
        print(json.dumps({'pages': len(pages), 'pdfSha256': result['sha256'], 'warningCount': len(warnings.splitlines())}))

if __name__ == '__main__': main()
