"""Read DrawingExportTest/DxfExportTest output with PyMuPDF, ezdxf and Pillow.

Usage: python scripts/validate_drawing_exports.py [JVM-temp/eezy-2d-export]
"""
import math
import pathlib
import sys

import ezdxf
import pymupdf
from PIL import Image

folder = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else '/tmp/eezy-2d-export')
required = {'A3-50.pdf', 'A3-100.pdf', 'A1-50.pdf', 'A1-100.pdf', 'level.pdf', 'view.pdf', 'annotations.pdf', 'with-underlay.pdf', 'no-underlay.pdf', 'rotated-underlay.pdf', 'plan-M.dxf', 'plan-CM.dxf', 'plan-MM.dxf', 'level.dxf', 'dashed.dxf', 'underlay.dxf', 'with-underlay.dxf', 'annotations.dxf', 'level.png', 'underlay-on.png', 'underlay-off.png'}
assert required.issubset({p.name for p in folder.iterdir()}), 'Run DrawingExportTest and DxfExportTest first'
for path in folder.glob('*.pdf'):
    with pymupdf.open(path) as pdf:
        assert not pdf.is_repaired and len(pdf) == 1, path.name
        page = pdf[0]
        assert page.get_pixmap().width > 0
        assert 'Office plan' in page.get_text(), path.name
        if path.stem.startswith(('A3-', 'A1-')):
            paper, scale = path.stem.split('-')
            width, height = {'A3': (420, 297), 'A1': (841, 594)}[paper]
            assert abs(page.rect.width - width*72/25.4) <= .51
            assert abs(page.rect.height - height*72/25.4) <= .51
            assert f'1:{int(scale)}.00' in page.get_text()
            assert '\nN\n' in page.get_text()
            length = 10*1000/int(scale)*72/25.4
            lines = [item for drawing in page.get_drawings() for item in drawing['items'] if item[0] == 'l']
            assert any(abs(math.dist(item[1], item[2])-length) < .001 for item in lines), (path.name, length)
        if path.stem == 'level':
            assert abs(page.rect.width-297*72/25.4) <= .51
            assert abs(page.rect.height-420*72/25.4) <= .51
        if path.stem == 'with-underlay':
            images = page.get_images(full=True)
            assert len(images) == 1 and images[0][1] > 0, 'Underlay needs an alpha mask'
            # The 50%-opaque red image remains pink over white paper, away from the line.
            r, g, b = page.get_pixmap().pixel(595, 300)
            assert r == 255 and 120 <= g <= 135 and 120 <= b <= 135
        if path.stem == 'rotated-underlay':
            image = page.get_pixmap()
            assert image.pixel(542, 318) == (255, 0, 0)
            assert image.pixel(649, 318) == (0, 0, 255)
        if path.stem == 'no-underlay':
            assert not page.get_images()
        if path.stem == 'annotations':
            assert len(page.get_drawings()) > 50, 'Dimensions/labels must retain vector strokes'
        print(path.name, 'PDF parse, render, paper/scale, decorations and underlay checks passed')

for path in folder.glob('*.dxf'):
    doc = ezdxf.readfile(path)
    audit = doc.audit()
    assert not audit.errors and not audit.fixes, (path.name, audit.errors, audit.fixes)
    model = doc.modelspace()
    if path.stem.startswith('plan-'):
        unit = path.stem.split('-')[1]
        factor, code = {'M': (1, 6), 'CM': (100, 5), 'MM': (1000, 4)}[unit]
        assert doc.units == code
        line = list(model.query('LINE'))
        assert len(line) == 1 and line[0].dxf.layer == 'Power'
        assert tuple(line[0].dxf.start) == (factor, factor, 0)
        assert tuple(line[0].dxf.end) == (3*factor, factor, 0)
    if path.stem == 'dashed':
        line = list(model.query('LINE'))
        assert len(line) == 1 and line[0].dxf.linetype == 'DASHED'
        assert [tag.value for tag in doc.linetypes.get('DASHED').pattern_tags.tags if tag.code == 49] == [120, -120]
    if path.stem in ['underlay', 'with-underlay']:
        solids = list(model.query('SOLID'))
        assert solids and all(s.dxf.layer == 'UNDERLAY' for s in solids)
        assert doc.layers.get('UNDERLAY').is_locked()
        assert not list(model.query('IMAGE')), 'DXF must not depend on an external image file'
    if path.stem == 'annotations':
        assert any(t.dxf.text == 'Room A1' and t.dxf.layer == 'Power' for t in model.query('TEXT'))
        assert len(model.query('LINE')) > 1
    print(path.name, 'DXF read/audit, units, layers, line types and embedded underlay checks passed')

for name in ['level.png', 'underlay-on.png', 'underlay-off.png']:
    with Image.open(folder/name) as image:
        assert image.size == (400, 240) and image.format == 'PNG'
        if name == 'underlay-on.png':
            r, g, b, *_ = image.getpixel((200, 100))
            assert r > g and r > b
        elif name == 'underlay-off.png':
            assert image.getpixel((200, 100))[:3] == (247, 250, 252)
    print(name, 'PNG read, view dimensions and underlay toggle passed')
print('All 2D export readbacks passed')
