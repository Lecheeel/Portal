"""Provision an independent signing identity in GitHub Secrets; never print secret values.

Local recovery material is kept outside the repository. Credentials use Windows DPAPI.
The build action reuses that identity; it never rotates a signing key implicitly.
"""
import argparse
import base64
import ctypes
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PREFIX = "INTEGRITY_"


def local_directory():
    if os.name != "nt":
        raise RuntimeError("Local provisioning uses Windows DPAPI. CI uses GitHub Secrets directly.")
    return Path(os.environ["LOCALAPPDATA"]) / "LocationIntegrity" / "signing"


def dpapi(value, decrypt=False):
    class Blob(ctypes.Structure):
        _fields_ = [("size", ctypes.c_ulong), ("data", ctypes.POINTER(ctypes.c_ubyte))]
    buffer = ctypes.create_string_buffer(value)
    source = Blob(len(value), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)))
    target = Blob()
    function = ctypes.windll.crypt32.CryptUnprotectData if decrypt else ctypes.windll.crypt32.CryptProtectData
    success = function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target))
    if not success:
        raise ctypes.WinError()
    try:
        return ctypes.string_at(target.data, target.size)
    finally:
        free = ctypes.windll.kernel32.LocalFree
        free.argtypes = [ctypes.c_void_p]
        free.restype = ctypes.c_void_p
        free(target.data)


def keytool():
    configured = os.environ.get("JAVA_HOME")
    candidates = [Path(configured) / "bin" / "keytool.exe"] if configured else []
    found = shutil.which("keytool")
    if found:
        candidates.append(Path(found))
    candidates.append(Path("C:/Program Files/Android/Android Studio/jbr/bin/keytool.exe"))
    return str(next(path for path in candidates if path.is_file()))


def credentials():
    path = local_directory() / "credentials.dpapi"
    if not path.is_file():
        raise RuntimeError("No local signing identity. Provision explicitly first.")
    return json.loads(dpapi(path.read_bytes(), decrypt=True))


def signing_environment(creds):
    env = os.environ.copy()
    env.update({PREFIX + "KEYSTORE_PATH": str(local_directory() / "release.p12"),
                PREFIX + "KEYSTORE_PASSWORD": creds["password"],
                PREFIX + "KEY_ALIAS": creds["alias"], PREFIX + "KEY_PASSWORD": creds["password"]})
    return env


def provision(repo):
    directory = local_directory()
    directory.mkdir(parents=True, exist_ok=True)
    # Restrict this new, explicitly scoped directory to the current Windows identity.
    import csv
    identity = next(csv.reader(subprocess.check_output(["whoami", "/user", "/fo", "csv", "/nh"], text=True).splitlines()))[1]
    subprocess.run(["icacls", str(directory), "/inheritance:r", "/grant:r", "*" + identity + ":(OI)(CI)F"], check=True, capture_output=True)
    key = directory / "release.p12"
    protected = directory / "credentials.dpapi"
    if key.exists() != protected.exists():
        raise RuntimeError("Incomplete local signing backup; refusing to replace or rotate the key.")
    existing = json.loads(subprocess.check_output(["gh", "secret", "list", "--repo", repo, "--json", "name"], text=True))
    if not key.exists() and any(item["name"].startswith(PREFIX + "KEY") for item in existing):
        raise RuntimeError("Signing secrets already exist but the local backup is absent. Refusing to rotate.")
    if not key.exists():
        creds = {"alias": "location-integrity", "password": secrets.token_urlsafe(48)}
        protected.write_bytes(dpapi(json.dumps(creds).encode()))
        env = signing_environment(creds)
        subprocess.run([keytool(), "-genkeypair", "-noprompt", "-storetype", "PKCS12", "-keystore", str(key),
                        "-alias", creds["alias"], "-storepass:env", PREFIX + "KEYSTORE_PASSWORD",
                        "-keypass:env", PREFIX + "KEY_PASSWORD", "-keyalg", "RSA", "-keysize", "3072",
                        "-sigalg", "SHA256withRSA", "-validity", "10000", "-dname", "CN=Location Integrity, O=Lecheeel"],
                       check=True, env=env, capture_output=True)
    creds = credentials()
    certificate = subprocess.check_output([keytool(), "-exportcert", "-keystore", str(key), "-alias", creds["alias"],
                                           "-storepass:env", PREFIX + "KEYSTORE_PASSWORD"], env=signing_environment(creds))
    fingerprint = hashlib.sha256(certificate).hexdigest()
    pin = ROOT / "integrity-app" / "release-certificate.sha256"
    if pin.exists() and pin.read_text().strip() != fingerprint:
        raise RuntimeError("Certificate does not match the repository pin. Refusing key rotation.")
    pin.write_text(fingerprint + chr(10), encoding="ascii")
    values = {PREFIX + "KEYSTORE_BASE64": base64.b64encode(key.read_bytes()).decode(),
              PREFIX + "KEYSTORE_PASSWORD": creds["password"], PREFIX + "KEY_ALIAS": creds["alias"],
              PREFIX + "KEY_PASSWORD": creds["password"]}
    for name, value in values.items():
        # Send via stdin, without exposing the value in process arguments or logs.
        subprocess.run(["gh", "secret", "set", name, "--repo", repo], input=value, text=True, check=True, capture_output=True)
        print("Configured " + name)
    print("Public certificate SHA-256: " + fingerprint)
    print("Encrypted local recovery material: " + str(directory))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["provision", "build"])
    parser.add_argument("--repo", default="Lecheeel/Portal")
    args = parser.parse_args()
    if args.action == "provision":
        provision(args.repo)
    else:
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        command = [str(ROOT / "gradlew.bat"), ":integrity-app:assembleRelease", "-PBUILD_REVISION=" + revision, "--offline", "--console=plain"]
        raise SystemExit(subprocess.run(command, cwd=ROOT, env=signing_environment(credentials())).returncode)


if __name__ == "__main__":
    main()
