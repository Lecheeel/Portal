"""Generate GLONASS vacuum reception vectors with separately compiled pinned RTKLIB C.

Requires ephemeris.c, rtkcmn.c, rtklib.h from 71db0ffa0d9735697c6adfd06fdf766d0e5ce807.
Inputs are the real 2013 orbital parameters in glonass-reference.json and synthetic explicitly
declared PZ-90.02 receiver positions/normals. No datum, clock or calendar conversion is tested.
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
    parser.add_argument('--cc', default='gcc')
    args = parser.parse_args()
    source_path = args.rtklib_dir / 'ephemeris.c'
    if hashlib.sha256(source_path.read_bytes()).hexdigest() != \
            'c0e5aa04b1dc2f098f2ac3399c43410323ad44f8122f52f806f122a3ac52c626':
        raise ValueError('ephemeris.c does not match the pinned source')
    resources = Path(__file__).resolve().parents[1] / 'core/src/test/resources/gnss'
    records = json.loads((resources / 'glonass-reference.json').read_text())['records']
    receivers = [(0, 0, [6378136, 0, 0]), (31.2, 121.5, [-2850000, 4650000, 3290000]),
                 (90, -179.9, [0, 0, 6356750])]
    cases, statements = [], []
    for record_index, r in enumerate(records):
        for seconds, (lat, lon, xyz) in zip((-600, 0, 600), receivers, strict=True):
            cases.append(dict(recordIndex=record_index, offsetSeconds=seconds, latitudeDegrees=lat,
                              longitudeDegrees=lon, receiverEcefMeters=xyz))
            init = ','.join(format(v, '.17g') for v in r['positionMeters'] + r['velocityMetersPerSecond'])
            acc = ','.join(format(v, '.17g') for v in r['luniSolarAccelerationMetersPerSecondSquared'])
            statements.append('''{ double initial[6]={''' + init + '},acc[3]={' + acc + '};' + f'''
double x[6],rot[3],rr[3]={{{','.join(map(str,xyz))}}},pos[3]={{{lat}*D2R,{lon}*D2R,0}},enu[3],diff[3];
double tau=.1,next,remaining,h,angle,rho;int i,j;
for(i=0;i<100;i++){{
for(j=0;j<6;j++)x[j]=initial[j];remaining={seconds}-tau;
while(fabs(remaining)>1e-12){{h=remaining<0?-fmin(-remaining,1):fmin(remaining,1);glorbit(h,x,acc);remaining-=h;}}
angle=-OMGE_GLO*tau;rot[0]=x[0]*cos(angle)-x[1]*sin(angle);
rot[1]=x[0]*sin(angle)+x[1]*cos(angle);rot[2]=x[2];
for(j=0;j<3;j++)diff[j]=rot[j]-rr[j];rho=norm(diff,3);next=rho/CLIGHT;
if(fabs(next-tau)<1e-15)break;tau=next;
}}
if(i==100)return 2;ecef2enu(pos,diff,enu);
printf("%.17g %.17g %.17g %.17g %.17g %.17g %.17g %.17g %.17g %.17g\\n",tau,
rot[0],rot[1],rot[2],rho,enu[0],enu[1],enu[2],atan2(enu[2],hypot(enu[0],enu[1]))*R2D,
fmod(atan2(enu[0],enu[1])*R2D+360,360));
}}''')
    with tempfile.TemporaryDirectory(prefix='glonass-reception-reference-') as temp:
        temp = Path(temp)
        prefix = source_path.read_text().split('/* sbas ephemeris to satellite clock bias')[0]
        (temp / 'reference.c').write_text(prefix + '\nint main(void){\n' + '\n'.join(statements) + '\nreturn 0;}')
        exe = temp / 'reference.exe'
        command = [args.cc, '-O2', '-I', str(args.rtklib_dir), str(temp / 'reference.c'),
                   str(args.rtklib_dir / 'rtkcmn.c')]
        command += ['-DWIN32', '-lwinmm', '-lws2_32'] if os.name == 'nt' else ['-lm', '-lpthread']
        subprocess.run(command + ['-o', str(exe)], check=True)
        result = subprocess.run([str(exe)], capture_output=True, text=True, check=True)
    names = ['propagationSeconds','xMeters','yMeters','zMeters','rangeMeters','eastMeters','northMeters',
             'upMeters','elevationDegrees','azimuthDegrees']
    for case, line in zip(cases, result.stdout.splitlines(), strict=True):
        case['expected'] = dict(zip(names, map(float, line.split()), strict=True))
    output = dict(reference='https://github.com/tomojitakasu/RTKLIB/blob/'
                  '71db0ffa0d9735697c6adfd06fdf766d0e5ce807/src/ephemeris.c',
                  method='C glorbit at 1s steps and fixed-point vacuum light-time, ecef2enu. '
                  '2013 RINEX orbital parameters; synthetic explicit PZ-90.02 receiver origins/normals. '
                  'Not datum/time conversion or signal validity evidence.', cases=cases)
    (resources / 'glonass-reception-reference.json').write_text(json.dumps(output, indent=2) + '\n')
    print(f'Generated {len(cases)} independent C GLONASS reception cases')


if __name__ == '__main__':
    main()
