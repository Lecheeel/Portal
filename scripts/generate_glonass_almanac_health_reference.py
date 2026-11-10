"""Generate synthetic GLONASS almanac-health fields verified by pinned RTKLIB C.

Checks parity and raw nA/MnA/Cn extraction, not full almanac decoding, date association,
current service applicability, or physical receiver output.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rtklib-dir', type=Path, required=True)
    parser.add_argument('--cc', default='gcc')
    args = parser.parse_args()
    source_path = args.rtklib_dir / 'rcvraw.c'
    if hashlib.sha256(source_path.read_bytes()).hexdigest() != \
            '44e6bbdb250543c74d466bad870ecd8f472e65b5ad1b88177ca5aadb1ef08a17':
        raise ValueError('rcvraw.c does not match the pinned source')
    source = source_path.read_text()
    matrix = re.search(r'mask_hamming\[\]\[12\]=\{(.*?)\n    \};', source, re.S)
    masks = re.findall(r'0x[0-9A-Fa-f]+', matrix[1])
    if len(masks) != 88:
        raise ValueError('invalid Hamming masks')
    masks = [int(''.join(v[2:].zfill(2) for v in masks[i:i+11]), 16) for i in range(0, 88, 11)]
    rows = []
    # Cover all even string identities, both health/type values, and distinct sender/target.
    for frame, string_id, target, satellite_type, cn in [(1, 6, 24, 1, 1), (2, 8, 1, 0, 0),
            (3, 10, 7, 1, 0), (5, 12, 23, 0, 1), (4, 14, 19, 1, 1)]:
        # Nonzero irrelevant orbital bits ensure these are not all-zero blank payloads.
        word = (string_id << 80) | (cn << 79) | (satellite_type << 77) | (target << 72) | 0x159acefedcba987600
        for parity in range(256):
            candidate = word | parity
            if all(((candidate << 3) & mask).bit_count() % 2 == 0 for mask in masks):
                break
        else:
            raise ValueError('no parity solution')
        rows.append(dict(frameId=frame, stringId=string_id, targetSlot=target, satelliteTypeBits=satellite_type,
                         healthCn=cn, androidBase64=base64.b64encode(candidate.to_bytes(11, 'big')).decode(),
                         cPayload=(candidate << 3).to_bytes(11, 'big').hex()))
    with tempfile.TemporaryDirectory(prefix='glonass-almanac-health-reference-') as directory:
        directory = Path(directory)
        prefix = source.split('/* decode glonass ephemeris strings')[0]
        driver = prefix + '''
int main(void){unsigned char b[11];unsigned int v;int i;
for(i=0;i<11;i++){if(scanf("%2x",&v)!=1)return 2;b[i]=(unsigned char)v;}
if(!test_glostr(b))return 3;
printf("%u %u %u %u\\n",getbitu(b,1,4),getbitu(b,8,5),getbitu(b,6,2),getbitu(b,5,1));return 0;}
'''
        (directory / 'reference.c').write_text(driver)
        exe = directory / 'reference.exe'
        command = [args.cc, '-O2', '-I', str(args.rtklib_dir), str(directory / 'reference.c'),
                   str(args.rtklib_dir / 'rtkcmn.c')]
        command += ['-DWIN32', '-lwinmm', '-lws2_32'] if os.name == 'nt' else ['-lm', '-lpthread']
        subprocess.run(command + ['-o', str(exe)], check=True)
        for row in rows:
            result = subprocess.run([str(exe)], input=row.pop('cPayload'), capture_output=True, text=True, check=True)
            values = list(map(int, result.stdout.split()))
            if values != [row['stringId'], row['targetSlot'], row['satelliteTypeBits'], row['healthCn']]:
                raise ValueError('C field extraction differs from synthetic input')
    output = dict(reference='https://github.com/tomojitakasu/RTKLIB/blob/'
                  '71db0ffa0d9735697c6adfd06fdf766d0e5ce807/src/rcvraw.c',
                  method='Synthetic Android GLONASS almanac payloads; independent C parity and raw '
                  'unsigned field extraction only. Not full almanac decoding or current service evidence.', records=rows)
    target = Path(__file__).resolve().parents[1] / 'core/src/test/resources/gnss/glonass-almanac-health-reference.json'
    target.write_text(json.dumps(output, indent=2) + '\n')
    print('Generated five synthetic GLONASS almanac-health C vectors')


if __name__ == '__main__':
    main()
