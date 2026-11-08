"""Read sample fixtures independently after TestBuildingExportTest.

Use the regular Python with PyMuPDF, ezdxf, openpyxl and Pillow; --model-python
selects an interpreter containing ifcopenshell, trimesh and pygltflib.
"""
import argparse
import csv
from pathlib import Path
import subprocess
import sys
import fitz
import ezdxf
import openpyxl
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument('folder', nargs='?', default='/tmp/eezy-test-building-exports')
parser.add_argument('--model-python', default=sys.executable)
args = parser.parse_args()
folder = Path(args.folder)
assert folder.is_dir(), 'Run TestBuildingExportTest first'
subprocess.run([args.model_python, str(Path(__file__).with_name('validate_model_exports.py')), str(folder)], check=True)
for path in folder.glob('*.pdf'):
    with fitz.open(path) as pdf:
        assert pdf.page_count > 0
        page = pdf.load_page(0)
        assert page.rect.width > 0 and page.rect.height > 0
        assert page.get_pixmap(matrix=fitz.Matrix(.25, .25)).samples
        print(path.name, pdf.page_count, 'PDF pages read and rendered')
plan = ezdxf.readfile(folder / 'plan.dxf')
assert not plan.audit().has_errors
assert plan.header['$INSUNITS'] == 4
assert len(plan.modelspace()) > 0
print('plan.dxf', 'audit, millimetres and entities passed')
for path in folder.glob('*.png'):
    with Image.open(path) as image:
        assert image.width == 1000 and image.height > 0
        image.verify()
    print(path.name, 'PNG decoded')
for path in folder.glob('*.xlsx'):
    book = openpyxl.load_workbook(path, read_only=True, data_only=True)
    assert book.worksheets and sum(1 for _ in book.worksheets[0].rows) > 1
    book.close()
    print(path.name, 'OpenXML workbook read')
csv.field_size_limit(32 * 1024 * 1024)
for path in folder.glob('*.csv'):
    with path.open(newline='', encoding='utf-8-sig') as stream:
        rows = list(csv.reader(stream))
    assert len(rows) > 1
    print(path.name, len(rows), 'CSV rows read')
