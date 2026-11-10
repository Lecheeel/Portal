"""Generate GLONASS orbital test vectors using pinned RTKLIB C, without downloads.

Supply ephemeris.c, rtkcmn.c, rtklib.h from RTKLIB commit
71db0ffa0d9735697c6adfd06fdf766d0e5ce807 and the georinex fixture
BRDM00DLR_R_20130010000_01D_MN.rnx at commit
8d1210a0f1ada71ff7b8d0484cfaf22ff154a38e. Only test resources are written.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rtklib-dir', type=Path, required=True)
    parser.add_argument('--rinex', type=Path, required=True)
    parser.add_argument('--cc', default='gcc')
    args = parser.parse_args()
    expected_input_hash = 'f70160c0bc3d26cf4878fe5aecc333befc8d2c7012372a1318aa21f1f1b3fe76'
    expected_source_hash = 'c0e5aa04b1dc2f098f2ac3399c43410323ad44f8122f52f806f122a3ac52c626'
    if hashlib.sha256(args.rinex.read_bytes()).hexdigest() != expected_input_hash:
        raise ValueError('RINEX input does not match the pinned fixture')
    if hashlib.sha256((args.rtklib_dir / 'ephemeris.c').read_bytes()).hexdigest() != expected_source_hash:
        raise ValueError('ephemeris.c does not match the pinned RTKLIB source')
    lines = args.rinex.read_text().splitlines()
    records = []
    # All four genuine 2013 GLONASS records, before the PZ-90.11 transition.
    for i, line in enumerate(lines):
        if not line.startswith('R'):
            continue
        axes = [[float(lines[i + k][4 + j * 19:23 + j * 19]) for j in range(3)] for k in (1, 2, 3)]
        records.append(dict(slot=int(line[1:3]), referenceUtcLabel=line[4:23].strip(),
                            referenceFrame='PZ_90_02', positionMeters=[a[0] * 1000 for a in axes],
                            velocityMetersPerSecond=[a[1] * 1000 for a in axes],
                            luniSolarAccelerationMetersPerSecondSquared=[a[2] * 1000 for a in axes]))
    if len(records) != 4:
        raise ValueError('expected exactly four records from the pinned 2013 fixture')
    source = (args.rtklib_dir / 'ephemeris.c').read_text()
    marker = '/* sbas ephemeris to satellite clock bias'
    if source.count(marker) != 1:
        raise ValueError('unexpected RTKLIB source layout')
    statements = []
    for r in records:
        r['expected'] = []
        for offset in (-899, -617.25, -60.125, 0, 60.125, 617.25, 899):
            case = dict(offsetSeconds=offset)
            r['expected'].append(case)
            init = ','.join(format(v, '.17g') for v in r['positionMeters'] + r['velocityMetersPerSecond'])
            acc = ','.join(format(v, '.17g') for v in r['luniSolarAccelerationMetersPerSecondSquared'])
            # Call the original static RK4 routine; compare 60, 10 and 1 second grids.
            # Relative intervals only: no pretend GPS/UTC/GLONASS conversion in the oracle.
            for step in (60, 10, 1):
                statements.append('''{ double x[6]={''' + init + '},acc[3]={' + acc + '};' + f'''
double remaining={offset:.17g},h; int i;
while(fabs(remaining)>1e-12){{
h=remaining<0?-fmin(-remaining,{step}):fmin(remaining,{step});
glorbit(h,x,acc);remaining-=h;
}}
for(i=0;i<6;i++)printf("%.17g ",x[i]);printf("\\n");
}}''')
    with tempfile.TemporaryDirectory(prefix='glonass-reference-') as temp:
        temp = Path(temp)
        driver = temp / 'reference.c'
        # Preserve the original prefix and copyright; include it so static glorbit is callable.
        driver.write_text(source.split(marker)[0] + '\nint main(void){\n' + '\n'.join(statements) + '\nreturn 0;}')
        exe = temp / 'reference.exe'
        command = [args.cc, '-O2', '-I', str(args.rtklib_dir), str(driver), str(args.rtklib_dir / 'rtkcmn.c')]
        command += ['-DWIN32', '-lwinmm', '-lws2_32'] if os.name == 'nt' else ['-lm', '-lpthread']
        subprocess.run(command + ['-o', str(exe)], check=True)
        results = iter(subprocess.run([str(exe)], capture_output=True, text=True, check=True).stdout.splitlines())
    for r in records:
        for case in r['expected']:
            for step in (60, 10, 1):
                vector = list(map(float, next(results).split()))
                if len(vector) != 6:
                    raise ValueError('invalid C output')
                case[f'stateStep{step}'] = vector
    if next(results, None) is not None:
        raise ValueError('unexpected C output')
    output = dict(input='https://github.com/geospace-code/georinex/blob/'
                  '8d1210a0f1ada71ff7b8d0484cfaf22ff154a38e/src/georinex/tests/data/'
                  'BRDM00DLR_R_20130010000_01D_MN.rnx',
                  algorithm='https://github.com/tomojitakasu/RTKLIB/blob/'
                  '71db0ffa0d9735697c6adfd06fdf766d0e5ce807/src/ephemeris.c',
                  method='Original C glorbit RK4 on three step grids. Real RINEX parameters, not raw '
                  'receiver strings or current constellation evidence; no calendar/time conversion.', records=records)
    target = Path(__file__).resolve().parents[1] / 'core/src/test/resources/gnss/glonass-reference.json'
    # Compact numeric vectors keep the generated fixture reviewable.
    content = json.dumps(output, indent=2)
    import re
    content = re.sub(r'\[\s+([-\d.eE+,\s]+)\s+\]',
                     lambda m: '[' + ', '.join(v.strip() for v in m[1].strip().split(',')) + ']', content)
    target.write_text(content + '\n')
    print(f'Generated {len(records) * 7} independent C GLONASS propagation cases on three step grids')


if __name__ == '__main__':
    main()
