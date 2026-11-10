"""Generate numerical fixtures with a separately compiled pinned RTKLIB implementation.

Requires ephemeris.c, rtkcmn.c and rtklib.h from
RTKLIB 71db0ffa0d9735697c6adfd06fdf766d0e5ce807 in --rtklib-dir. No downloads are made.
Example: python scripts/generate_galileo_reception_reference.py --rtklib-dir PATH
This is numerical verification, not receiver measurements or datum conversion evidence.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rtklib-dir", type=Path, required=True)
    parser.add_argument("--cc", default="gcc")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    resources = root / "core/src/test/resources/gnss"
    records = json.loads((resources / "galileo-reference.json").read_text())["records"]
    mapping = dict(e="e", i0="i0", omega0="OMG0", omega="omg", m0="M0", deltaN="deln",
                   omegaDot="OMGd", iDot="idot", crc="crc", crs="crs", cuc="cuc", cus="cus",
                   cic="cic", cis="cis", af0="f0", af1="f1", af2="f2")
    # Explicit synthetic GTRF origins/local orientations; no pos2ecef/datum transformation.
    receivers = [(0, 0, [6378137, 0, 0]), (31.2, 121.5, [-2850000, 4650000, 3290000]),
                 (90, -179.9, [0, 0, 6356752.314245179])]
    cases, statements = [], []
    for index, r in enumerate(records):
        for offset, (lat, lon, xyz) in zip((-900, 0, 900), receivers, strict=True):
            cases.append(dict(recordIndex=index, offsetSeconds=offset,
                              latitudeDegrees=lat, longitudeDegrees=lon, receiverEcefMeters=xyz))
            init = "".join(f"e.{field}={r[key]:.17g};" for key, field in mapping.items())
            init += f'e.sat=satno(SYS_GAL,{r["svid"]});e.A={r["sqrtA"]**2:.17g};'
            init += f'e.toes={r["toe"]:.17g};'
            # RTKLIB absolute time is only a container for the GST relative intervals here.
            init += f'e.toe=gpst2time({int(r["rinexGpsWeek"])},{r["toe"]});'
            init += f'e.toc=gpst2time({int(r["rinexGpsWeek"])},{r["toc"]});'
            statements.append('''{ eph_t e={0}; double rs[3],dts,var,rr[3],pos[3],rot[3],diff[3],enu[3];
double tau=.1,next,theta,rho; int i,j;
''' + init + f'''
if(!e.sat)return 3;
rr[0]={xyz[0]:.17g};rr[1]={xyz[1]:.17g};rr[2]={xyz[2]:.17g};
pos[0]={lat:.17g}*D2R;pos[1]={lon:.17g}*D2R;pos[2]=0;
for(i=0;i<100;i++) {{
eph2pos(timeadd(e.toe,{offset}-tau),&e,rs,&dts,&var);
theta=-OMGE*tau;
rot[0]=rs[0]*cos(theta)-rs[1]*sin(theta);
rot[1]=rs[0]*sin(theta)+rs[1]*cos(theta);rot[2]=rs[2];
for(j=0;j<3;j++)diff[j]=rot[j]-rr[j];
rho=norm(diff,3);next=rho/CLIGHT;
if(fabs(next-tau)<1e-15)break;
tau=next;
}}
if(i==100)return 2;
ecef2enu(pos,diff,enu);
printf("%.17g %.17g %.17g %.17g %.17g %.17g %.17g %.17g %.17g %.17g %.17g\\n",
tau,rot[0],rot[1],rot[2],rho,enu[0],enu[1],enu[2],
atan2(enu[2],hypot(enu[0],enu[1]))*R2D,fmod(atan2(enu[0],enu[1])*R2D+360,360),dts);
}}''')
    with tempfile.TemporaryDirectory(prefix="galileo-reference-") as temp:
        driver = Path(temp) / "reference.c"
        executable = Path(temp) / "reference.exe"
        # Keep the original prefix through eph2pos, including copyright and helpers;
        # later orbit/SSR entry points require unrelated RTKLIB translation units.
        source = (args.rtklib_dir / "ephemeris.c").read_text()
        marker = "/* glonass orbit differential equations"
        if source.count(marker) != 1:
            raise ValueError("unexpected RTKLIB ephemeris.c layout; use the pinned commit")
        ephemeris = Path(temp) / "ephemeris-prefix.c"
        ephemeris.write_text(source.split(marker)[0])
        driver.write_text('#include "rtklib.h"\nint main(void){\n' + "\n".join(statements) + "\nreturn 0;}")
        command = [args.cc, "-O2", "-DENAGAL", "-I", str(args.rtklib_dir), str(driver),
                   str(ephemeris), str(args.rtklib_dir / "rtkcmn.c")]
        import os
        command += ["-lwinmm", "-lws2_32"] if os.name == "nt" else ["-lm", "-lpthread"]
        subprocess.run(command + ["-o", str(executable)], check=True)
        result = subprocess.run([str(executable)], capture_output=True, text=True, check=True)
    names = ["propagationSeconds", "xMeters", "yMeters", "zMeters", "rangeMeters", "eastMeters",
             "northMeters", "upMeters", "elevationDegrees", "azimuthDegrees", "clockBiasSeconds"]
    for case, line in zip(cases, result.stdout.splitlines(), strict=True):
        case["expected"] = dict(zip(names, map(float, line.split()), strict=True))
    output = dict(reference="https://github.com/tomojitakasu/RTKLIB/blob/"
                  "71db0ffa0d9735697c6adfd06fdf766d0e5ce807/src/ephemeris.c",
                  parameters="galileo-reference.json; four E24 records, not four satellites",
                  method="RTKLIB eph2pos/ecef2enu; independent fixed-point vacuum light-time and reception-axis rotation. "
                  "Synthetic explicit GTRF origins and local normals, no datum or timescale conversion claim.", cases=cases)
    (resources / "galileo-reception-reference.json").write_text(json.dumps(output, indent=2) + "\n")
    print(f"Generated {len(cases)} independent Galileo reception vectors")


if __name__ == "__main__":
    main()
