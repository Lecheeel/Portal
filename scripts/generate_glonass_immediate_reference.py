"""Rebuild GLONASS-M immediate field vectors using pinned RTKLIB C decode_glostr.

Orbital parameters come from glonass-reference.json (2013 RINEX). All other fields and
the raw strings are SYNTHETIC. This does not validate absolute epoch/leap association.
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
    resources = Path(__file__).resolve().parents[1] / 'core/src/test/resources/gnss'
    records = json.loads((resources / 'glonass-reference.json').read_text())['records']
    rows = []
    for index, r in enumerate(records):
        raw = dict(slot=r['slot'], tauN=(-1)**index * (123457 + index), gammaN=(-1)**(index+1) * (347 + index),
                   deltaTauN=(-1)**index * 13, tb=31+index*2, day=800+index, hours=13, minutes=24, halfMinute=1,
                   bn=index+2, ln=index % 2, ft=index+3, age=9+index, p1=index % 4,
                   p2=1, p3=index % 2, p4=1, p=index % 4)
        words = [0] * 4
        def put(string, high, length, value, signed=False):
            if signed:
                value = abs(value) | (int(value < 0) << (length-1))
            if value < 0 or value >= 1 << length:
                raise ValueError('field exceeds width')
            words[string-1] |= value << (high-length)
        for string in range(1, 5):
            put(string, 84, 4, string)
        for axis in range(3):
            for name, high, bits, scale in [('positionMeters',35,27,1000/2048),
                    ('velocityMetersPerSecond',64,24,1000/1048576),
                    ('luniSolarAccelerationMetersPerSecondSquared',40,5,1000/1073741824)]:
                put(axis+1, high, bits, round(r[name][axis]/scale), True)
        fields = [(1,76,5,'hours'),(1,71,6,'minutes'),(1,65,1,'halfMinute'),(1,78,2,'p1'),
                  (2,80,3,'bn'),(2,77,1,'p2'),(2,76,7,'tb'),(3,80,1,'p3'),(3,79,11,'gammaN'),
                  (3,67,2,'p'),(3,65,1,'ln'),(4,80,22,'tauN'),(4,58,5,'deltaTauN'),
                  (4,53,5,'age'),(4,34,1,'p4'),(4,33,4,'ft'),(4,26,11,'day'),(4,15,5,'slot')]
        for string, high, bits, name in fields:
            put(string, high, bits, raw[name], name in ('gammaN','tauN','deltaTauN'))
        put(4, 10, 2, 1)  # GLONASS-M
        payloads = []
        for word in words:
            for parity in range(256):
                candidate = word | parity
                if all(((candidate << 3) & mask).bit_count() % 2 == 0 for mask in masks):
                    break
            else:
                raise ValueError('no parity solution')
            payloads.append(base64.b64encode(candidate.to_bytes(11, 'big')).decode())
        # C decoder takes 77 information bits left-aligned within each ten-byte block.
        c_input = b''.join(((word >> 8) << 3).to_bytes(10, 'big') for word in words).hex()
        rows.append(dict(rawUnits=raw, androidBase64=payloads, cInput=c_input))
    with tempfile.TemporaryDirectory(prefix='glonass-immediate-reference-') as temp:
        temp = Path(temp)
        prefix = source.split('/* decode gps/qzss navigation data subframe 1')[0]
        driver = prefix + '''
int main(void){unsigned char buff[40];unsigned int b;int i;geph_t g={0};
for(i=0;i<40;i++){if(scanf("%2x",&b)!=1)return 2;buff[i]=(unsigned char)b;}
g.tof=gpst2time(1721,172816);if(!decode_glostr(buff,&g))return 3;
for(i=0;i<3;i++)printf("%.17g ",g.pos[i]);
for(i=0;i<3;i++)printf("%.17g ",g.vel[i]);
for(i=0;i<3;i++)printf("%.17g ",g.acc[i]);
printf("%.17g %.17g %.17g %d %d %d %d\\n",g.taun,g.gamn,g.dtaun,g.svh,g.sva,g.age,g.iode);return 0;}
'''
        (temp / 'reference.c').write_text(driver)
        exe = temp / 'reference.exe'
        command = [args.cc, '-O2', '-DENAGLO', '-I', str(args.rtklib_dir), str(temp / 'reference.c'),
                   str(args.rtklib_dir / 'rtkcmn.c')]
        command += ['-DWIN32', '-lwinmm', '-lws2_32'] if os.name == 'nt' else ['-lm', '-lpthread']
        subprocess.run(command + ['-o', str(exe)], check=True)
        for row in rows:
            result = subprocess.run([str(exe)], input=row.pop('cInput'), capture_output=True, text=True, check=True)
            numbers = list(map(float, result.stdout.split()))
            if len(numbers) != 16:
                raise ValueError('invalid C output')
            row['expected'] = dict(positionMeters=numbers[:3], velocityMetersPerSecond=numbers[3:6],
                                   luniSolarAccelerationMetersPerSecondSquared=numbers[6:9],
                                   **dict(zip(['tauNSeconds','gammaN','deltaTauNSeconds','bn','ft','age','tb'], numbers[9:])))
    output = dict(reference='https://github.com/tomojitakasu/RTKLIB/blob/'
                  '71db0ffa0d9735697c6adfd06fdf766d0e5ce807/src/rcvraw.c',
                  method='Synthetic raw strings: orbital parameters quantized from glonass-reference.json; '
                  'clock/date/flags explicitly synthetic. C decode_glostr field oracle; no absolute time validation.', records=rows)
    content = json.dumps(output, indent=2)
    content = re.sub(r'\[\s+([-\d.eE+,\s]+)\s+\]',
                     lambda m: '[' + ', '.join(v.strip() for v in m[1].strip().split(',')) + ']', content)
    (resources / 'glonass-immediate-reference.json').write_text(content + '\n')
    print('Generated four independent C GLONASS-M immediate-field vectors')


if __name__ == '__main__':
    main()
