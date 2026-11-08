"""Read ScheduleExportTest output using openpyxl, PyMuPDF and Python's CSV reader.

Usage: python scripts/validate_schedule_exports.py [/tmp/eezy-schedule-export]
"""
import csv
import pathlib
import sys
import warnings

import openpyxl
import pymupdf

folder = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else '/tmp/eezy-schedule-export')
with warnings.catch_warnings(record=True) as caught:
    book = openpyxl.load_workbook(folder / 'boq.xlsx', data_only=False)
    sheet = book.active
    rows = list(sheet.values)
    assert not caught, caught
assert rows[0][:2] == ('Project', 'Schedule project')
assert rows[2][:2] == ('Date', '2026-11-08')
assert rows[3][:2] == ('Levels', 'Ground')
assert rows[8] == ('Item', 'Type', 'Size (mm)', 'Level', 'System', 'Unit', 'Quantity', 'Unit rate', 'Cost')
assert len(rows[9:89]) == 80
assert all(row[5:9] == ('ea', 1, 2.5, 2.5) for row in rows[9:89])
assert all(cell.data_type != 'f' for row in sheet for cell in row)
assert any(row[:2] == ('Priced total (USD)', 200) for row in rows)

with (folder / 'boq.csv').open(newline='') as source:
    csv_rows = list(csv.reader(source))
assert csv_rows[0] == ['Project', 'Schedule project']
assert csv_rows[8] == list(rows[8])
assert len(csv_rows[9:89]) == 80
assert all(row[1] == 'Device, quoted' for row in csv_rows[9:89])
assert ['Priced total (USD)', '200.00'] in csv_rows

pymupdf.TOOLS.mupdf_warnings()
with pymupdf.open(folder / 'boq.pdf') as pdf:
    assert not pdf.is_repaired and len(pdf) > 1
    text = ''
    for page in pdf:
        page_text = page.get_text()
        assert 'Schedule project' in page_text and 'Ground' in page_text and '2026-11-08' in page_text
        assert page.get_pixmap().width > 0
        text += page_text
    for index in range(80):
        assert f'Device {index}\n' in text
    assert 'Priced total (USD): 200.00' in text
assert not pymupdf.TOOLS.mupdf_warnings()
print('Schedule XLSX typed cells, CSV quoting/totals and multipage PDF parse/render passed')
