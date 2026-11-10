"""Generate synthetic BeiDou algorithm vectors with pinned RTKLIB C (not receiver evidence).

Requires ephemeris.c, rtkcmn.c, rtklib.h from RTKLIB commit
71db0ffa0d9735697c6adfd06fdf766d0e5ce807 in --rtklib-dir and a GCC-compatible --cc.
Only test resources are generated. No orbit catalog or production input is created.
"""
import argparse
import json
import math
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rtklib-dir", type=Path, required=True)
    parser.add_argument("--cc", default="gcc")
    args = parser.parse_args()
    # Deliberately synthetic, within the B1I fields. These are not real satellites/ephemerides.
    records = []
    for svid, kind, axis, inclination, eccentricity, mean in [
        (1, "GEO", 42164000, .07, .001, .2), (59, "GEO", 42164000, .07, .001, .2),
        (6, "IGSO", 42162000, .96, .003, -1.1), (20, "MEO", 27906000, .95, .01, 2.4),
    ]:
        records.append(dict(svid=svid, orbitType=kind, week=700, toe=432000, toc=432000,
            sqrtA=math.sqrt(axis), e=eccentricity, i0=inclination, omega0=.75, omega=-.83, m0=mean,
            deltaN=2.3e-9, omegaDot=-2.6e-9, iDot=1.2e-10, cuc=1.2e-6, cus=-3.4e-6,
            crc=180.25, crs=-23.5, cic=-2.1e-8, cis=4.3e-8, af0=1.2e-4, af1=2.3e-11, af2=-1e-19))
    mapping = dict(e="e", i0="i0", omega0="OMG0", omega="omg", m0="M0", deltaN="deln",
        omegaDot="OMGd", iDot="idot", crc="crc", crs="crs", cuc="cuc", cus="cus",
        cic="cic", cis="cis", af0="f0", af1="f1", af2="f2")
    statements = []
    for index, r in enumerate(records):
        init = "".join(f'e.{field}={r[key]:.17g};' for key, field in mapping.items())
        # Old RTKLIB chooses the GEO equation by PRN<=5. Map only the oracle branch,
        # retaining the explicit kind/SVID in test inputs, including modern GEO SVID 59.
        oracle_prn = 1 if r["orbitType"] == "GEO" else 6
        init += f'e.sat=satno(SYS_CMP,{oracle_prn});e.A={r["sqrtA"]**2:.17g};e.toes={r["toe"]};'
        # Absolute gtime_t is only a container for relative BDT differences and SOW here.
        init += f'e.toe=gpst2time({r["week"]},{r["toe"]});e.toc=gpst2time({r["week"]},{r["toc"]});'
        statements.append('{eph_t e={0};double rs[3],dts,var;' + init + 'if(!e.sat)return 2;')
        for offset in (-900, 0, 900):
            statements.append(f'eph2pos(timeadd(e.toe,{offset}),&e,rs,&dts,&var);'
                f'printf("{index} {offset} %.17g %.17g %.17g %.17g\\n",rs[0],rs[1],rs[2],dts);')
        statements.append('}')
    with tempfile.TemporaryDirectory(prefix="beidou-reference-") as temp:
        source = (args.rtklib_dir / "ephemeris.c").read_text()
        marker = "/* glonass orbit differential equations"
        if source.count(marker) != 1:
            raise ValueError("unexpected ephemeris.c layout; use the pinned commit")
        ephemeris = Path(temp) / "ephemeris-prefix.c"
        ephemeris.write_text(source.split(marker)[0])
        driver = Path(temp) / "driver.c"
        driver.write_text('#include "rtklib.h"\nint main(void){\n' + '\n'.join(statements) + '\nreturn 0;}')
        executable = Path(temp) / "reference.exe"
        command = [args.cc, "-O2", "-DENACMP", "-I", str(args.rtklib_dir), str(driver), str(ephemeris),
            str(args.rtklib_dir / "rtkcmn.c")]
        command += ["-lwinmm", "-lws2_32"] if os.name == "nt" else ["-lm", "-lpthread"]
        subprocess.run(command + ["-o", str(executable)], check=True)
        result = subprocess.run([str(executable)], capture_output=True, text=True, check=True)
    for line in result.stdout.splitlines():
        index, offset, x, y, z, clock = line.split()
        records[int(index)].setdefault("expected", []).append(dict(offsetSeconds=float(offset), xMeters=float(x),
            yMeters=float(y), zMeters=float(z), clockBiasSeconds=float(clock)))
    output = dict(reference="https://github.com/tomojitakasu/RTKLIB/blob/"
        "71db0ffa0d9735697c6adfd06fdf766d0e5ce807/src/ephemeris.c",
        method="Synthetic B1I algorithm inputs, independent RTKLIB C eph2pos with ENACMP. "
        "GEO oracle PRN=1, IGSO/MEO oracle PRN=6 selects the equations in this old C version. "
        "SVID is preserved in Kotlin inputs; no real receiver, orbit accuracy, or signal usability claim.", records=records)
    resource = Path(__file__).resolve().parents[1] / "core/src/test/resources/gnss/beidou-reference.json"
    resource.write_text(json.dumps(output, indent=2) + '\n')
    print("Generated twelve synthetic BeiDou algorithm vectors")


if __name__ == "__main__":
    main()
