"""Read IssueReviewTest's generated reports with independent readers.

Usage: python scripts/validate_issue_exports.py [/tmp]
Requires PyMuPDF and Pillow; run :app:testDebugUnitTest first.
"""
import base64
import csv
import io
import json
import pathlib
import sys

import pymupdf
from PIL import Image

folder = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else '/tmp')
pymupdf.TOOLS.mupdf_warnings()
with pymupdf.open(folder / 'issue-export-pdf.pdf') as pdf:
    assert len(pdf) == 2 and not pdf.is_repaired
    for page in pdf:
        assert pdf.xref_get_key(page.xref, 'Type')[1] == '/Page'
        assert page.get_pixmap().width > 0
        assert page.get_images(), 'Each issue must contain its captured view'
assert not pymupdf.TOOLS.mupdf_warnings(), 'PDF reader reported structural problems'

with Image.open(folder / 'issue-export-png.png') as image:
    assert image.format == 'PNG' and image.width == 1000 and image.height >= 1440
    image.load()

with (folder / 'issue-export-csv.csv').open(newline='') as source:
    rows = list(csv.DictReader(source))
assert len(rows) == 2 and rows[0]['Title'] == 'Test, quoted'
assert json.loads(rows[0]['View'])[0]['centre']['x'] == 2
for row in rows:
    data = row['Attachments'].split(';data:')[0]
    assert data.startswith('data:image/png;base64,')
    with Image.open(io.BytesIO(base64.b64decode(data.split(',', 1)[1]))) as image:
        image.load()
        assert image.size == (400, 300)
print('Issue PDF pages/render, PNG, CSV quoting, saved views and embedded screenshots passed')
