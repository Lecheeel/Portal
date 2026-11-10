"""Generate synthetic GLONASS-M string 5 bit/scale vectors with pinned RTKLIB C.

C test_glostr/getbitu/getbitg check parity and raw field extraction, NOT a complete
GLONASS time decoder, a clock conversion, an applicability interval or receiver data.
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
    # (N4, NA, tauC raw magnitude/sign, tauGPS raw magnitude/sign, ln, reserved bit 37)
    inputs = [(5, 800, 987654321, -123457, 0, 0),
              (31, 1461, 2147483647, 2097151, 1, 0),
              (1, 1, -2147483647, -2097151, 0, 1),
              (27, 1460, -1, 1, 1, 1),
              (1, 366, 0, 0, 0, 0)]
    rows = []
    for n4, na, tc, tg, ln, reserved in inputs:
        def signed_bits(value, length):
            return abs(value) | (int(value < 0) << (length - 1))
        word = (5 << 80) | (na << 69) | (signed_bits(tc, 32) << 37) | \
               (reserved << 36) | (n4 << 31) | (signed_bits(tg, 22) << 9) | (ln << 8)
        for parity in range(256):
            candidate = word | parity
            if all(((candidate << 3) & mask).bit_count() % 2 == 0 for mask in masks):
                break
        else:
            raise ValueError('no parity solution')
        rows.append(dict(rawUnits=dict(n4=n4, na=na, tauC=tc, tauGps=tg, ln=ln),
                         androidBase64=base64.b64encode(candidate.to_bytes(11, 'big')).decode(),
                         cPayload=(candidate << 3).to_bytes(11, 'big').hex()))
    with tempfile.TemporaryDirectory(prefix='glonass-time-reference-') as directory:
        directory = Path(directory)
        prefix = source.split('/* decode glonass ephemeris strings')[0]
        driver = prefix + '''
int main(void){unsigned char b[11];unsigned int v;int i;
for(i=0;i<11;i++){if(scanf("%2x",&v)!=1)return 2;b[i]=(unsigned char)v;}
if(!test_glostr(b)||getbitu(b,1,4)!=5)return 3;
printf("%u %u %.0f %.0f %u\\n",getbitu(b,49,5),getbitu(b,5,11),
getbitg(b,16,32),getbitg(b,54,22),getbitu(b,76,1));return 0;}
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
            if values != list(row['rawUnits'].values()):
                raise ValueError('C field extraction differs from synthetic input')
            row['expected'] = dict(zip(['n4', 'na', 'tauCRawUnits', 'tauGpsRawUnits', 'ln'], values))
    output = dict(reference='https://github.com/tomojitakasu/RTKLIB/blob/'
                  '71db0ffa0d9735697c6adfd06fdf766d0e5ce807/src/rcvraw.c',
                  method='Synthetic Android GLONASS-M string 5 payloads; independent C parity and raw '
                  'sign-magnitude/unsigned extraction only. Not receiver data or a time conversion.', records=rows)
    target = Path(__file__).resolve().parents[1] / 'core/src/test/resources/gnss/glonass-time-reference.json'
    target.write_text(json.dumps(output, indent=2) + '\n')
    print('Generated five synthetic GLONASS-M time-field C vectors')


if __name__ == '__main__':
    main()
