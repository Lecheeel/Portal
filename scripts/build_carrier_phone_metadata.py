"""Build compact offline phone generation metadata from a pinned Apache-2.0 source.

Usage: python scripts/build_carrier_phone_metadata.py <download-directory>
Required files: phone-number-metadata.xml, phone-cn-geocoding.txt,
phone-cn-carriers.txt, phone-metadata-license.txt.
Source revision: 36931eec42d4f57335ebf0360d6cb7eec1300d50 (google/libphonenumber).
No network access or source code execution is performed by this converter.
"""
import gzip
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parents[1]
source = pathlib.Path(sys.argv[1])
target = root / 'core/src/main/resources/carrier'
target.mkdir(parents=True, exist_ok=True)

def mapping(name):
    result = {}
    for line in (source / name).read_text(encoding='utf-8').splitlines():
        if line and not line.startswith('#'):
            key, value = line.split('|', 1)
            result[key] = value
    return result

def compact(text):
    return re.sub(r'\s+', '', text or '')

regions = []
for territory in ET.parse(source / 'phone-number-metadata.xml').getroot().findall('.//territory'):
    iso = territory.attrib['id']
    if iso == '001':
        continue
    descriptor = territory.find('mobile')
    kind = 'mobile'
    if descriptor is None or descriptor.findtext('exampleNumber') is None:
        descriptor = territory.find('fixedLine')
        kind = 'fixedLine'
    if descriptor is None or descriptor.findtext('exampleNumber') is None:
        continue
    regions.append(dict(iso=iso, callingCode=territory.attrib['countryCode'],
                        example=compact(descriptor.findtext('exampleNumber')),
                        pattern=compact(descriptor.findtext('nationalNumberPattern')),
                        kind=kind))

geography = mapping('phone-cn-geocoding.txt')
carriers = mapping('phone-cn-carriers.txt')
longer = {key[:9] for key in geography if key.startswith('861') and len(key) > 9}
rows = []
for prefix, city in geography.items():
    if not re.fullmatch(r'861\d{6}', prefix) or prefix in longer:
        continue
    operator = next((carriers[prefix[:size]] for size in range(len(prefix), 1, -1)
                     if prefix[:size] in carriers), '')
    code = {'China Mobile': 'CMCC', 'China Unicom': 'CUCC', 'China Telecom': 'CTCC',
            'China Broadnet': 'CBN'}.get(operator)
    if code:
        rows.append((city, code, prefix[2:]))
rows.sort()
assert len(rows) > 50000
payload = '\n'.join('|'.join(row) for row in rows) + '\n'
with (target / 'cn-mobile-cities.tsv.gz').open('wb') as output:
    with gzip.GzipFile(fileobj=output, mode='wb', mtime=0) as gz:
        gz.write(payload.encode('utf-8'))
(target / 'phone-regions.json').write_text(json.dumps(regions, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
(target / 'libphonenumber-LICENSE.txt').write_text((source / 'phone-metadata-license.txt').read_text(encoding='utf-8'), encoding='utf-8')
print(f'{len(regions)} regions; {len(rows)} Chinese mobile prefixes; {len(set(row[0] for row in rows))} city labels')
