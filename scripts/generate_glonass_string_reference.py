"""Build synthetic Android GLONASS strings, verified by pinned RTKLIB C test_glostr/getbitg.

Requires rcvraw.c, rtkcmn.c, rtklib.h from 71db0ffa0d9735697c6adfd06fdf766d0e5ce807.
This is a parity/bit-layout fixture, not captured receiver navigation data.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import random
import re
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rtklib-dir', type=Path, required=True)
    parser.add_argument('--cc', default='gcc')
    args = parser.parse_args()
    if hashlib.sha256((args.rtklib_dir / 'rcvraw.c').read_bytes()).hexdigest() != \
            '44e6bbdb250543c74d466bad870ecd8f472e65b5ad1b88177ca5aadb1ef08a17':
        raise ValueError('rcvraw.c does not match the pinned RTKLIB source')
    source = (args.rtklib_dir / 'rcvraw.c').read_text()
    matrix = re.search(r'mask_hamming\[\]\[12\]=\{(.*?)\n    \};', source, re.S)
    if matrix is None:
        raise ValueError('unexpected pinned RTKLIB Hamming table')
    masks = re.findall(r'0x[0-9A-Fa-f]+', matrix[1])
    if len(masks) != 88:
        raise ValueError('unexpected pinned RTKLIB Hamming dimensions')
    # The C oracle uses a LEFT-aligned 85-bit string with three low padding bits.
    masks = [int(''.join(v[2:].zfill(2) for v in masks[i:i+11]), 16) for i in range(0, 88, 11)]
    rng = random.Random(0)
    rows = []
    for string_id in range(1, 16):
        information = rng.getrandbits(76)
        information = (information & ~(15 << 72)) | (string_id << 72)
        for parity in range(256):
            word = (information << 8) | parity
            left_aligned = word << 3
            if all((left_aligned & mask).bit_count() % 2 == 0 for mask in masks):
                break
        else:
            raise ValueError('no parity solution')
        rows.append(dict(stringId=string_id,
                         androidBase64=base64.b64encode(word.to_bytes(11, 'big')).decode(),
                         cPayload=(word << 3).to_bytes(11, 'big').hex()))
    with tempfile.TemporaryDirectory(prefix='glonass-string-reference-') as temp:
        temp = Path(temp)
        # Preserve original copyright and checksum implementation; exclude unrelated decoders.
        prefix = source.split('/* decode glonass ephemeris strings')[0]
        driver = prefix + '''
int main(void){unsigned char buff[11];unsigned int b;int i;
for(i=0;i<11;i++){if(scanf("%2x",&b)!=1)return 2;buff[i]=(unsigned char)b;}
if(!test_glostr(buff))return 3;
printf("%u %.0f %.0f %.0f %u\\n",getbitu(buff,1,4),getbitg(buff,21,24),
getbitg(buff,45,5),getbitg(buff,50,27),getbitu(buff,5,22));return 0;}
'''
        (temp / 'reference.c').write_text(driver)
        exe = temp / 'reference.exe'
        command = [args.cc, '-O2', '-I', str(args.rtklib_dir), str(temp / 'reference.c'),
                   str(args.rtklib_dir / 'rtkcmn.c')]
        command += ['-DWIN32', '-lwinmm', '-lws2_32'] if os.name == 'nt' else ['-lm', '-lpthread']
        subprocess.run(command + ['-o', str(exe)], check=True)
        for row in rows:
            result = subprocess.run([str(exe)], input=row.pop('cPayload'), capture_output=True, text=True, check=True)
            values = list(map(int, result.stdout.split()))
            if len(values) != 5 or values[0] != row['stringId']:
                raise ValueError('unexpected C string identity/output')
            row['expected'] = dict(zip(['stringId', 'signed64_24', 'signed40_5', 'signed35_27', 'unsigned80_22'], values))
    output = dict(reference='https://github.com/tomojitakasu/RTKLIB/blob/'
                  '71db0ffa0d9735697c6adfd06fdf766d0e5ce807/src/rcvraw.c',
                  method='Synthetic 77-bit information plus solved Hamming parity using independent C byte masks; '
                  'C test_glostr and getbitg verify left-aligned strings. Android payloads are right-aligned '
                  '85-bit integers. Not receiver data or physical ephemerides.', strings=rows)
    target = Path(__file__).resolve().parents[1] / 'core/src/test/resources/gnss/glonass-strings-reference.json'
    target.write_text(json.dumps(output, indent=2) + '\n')
    print('Generated 15 synthetic GLONASS parity/bit-layout C vectors')


if __name__ == '__main__':
    main()
