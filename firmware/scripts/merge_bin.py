# Merge bootloader + partitions + app into one image at 0x0 for ESP Web Tools.
Import("env")

from pathlib import Path
import subprocess


# Use the supported Python module entrypoint rather than a generated Windows
# console launcher. pioarduino installs esptool in its build Python environment.
env.Replace(
    OBJCOPY='"$PYTHONEXE" -m esptool',
    ERASETOOL='"$PYTHONEXE" -m esptool',
)


def _boot_app0(env):
    pkg = Path(env.subst("$PROJECT_PACKAGES_DIR"))
    matches = list(pkg.glob("framework-arduinoespressif32*/tools/partitions/boot_app0.bin"))
    if not matches:
        matches = list(pkg.glob("**/boot_app0.bin"))
    if not matches:
        raise FileNotFoundError("boot_app0.bin not found under PlatformIO packages")
    return matches[0]


def merge_bin(source, target, env):
    build_dir = Path(env.subst("$BUILD_DIR"))
    project_dir = Path(env.subst("$PROJECT_DIR"))
    app = Path(env.subst("$BUILD_DIR/${PROGNAME}.bin"))
    bootloader = build_dir / "bootloader.bin"
    partitions = build_dir / "partitions.bin"
    boot_app0 = _boot_app0(env)
    out = project_dir / "dikey-usb-bridge-c3.bin"

    cmd = [
        env.subst("$PYTHONEXE"),
        "-m",
        "esptool",
        "--chip",
        "esp32c3",
        "merge-bin",
        "-o",
        str(out),
        "--flash-mode",
        "dio",
        "--flash-size",
        "4MB",
        "0x0",
        str(bootloader),
        "0x8000",
        str(partitions),
        "0xe000",
        str(boot_app0),
        "0x10000",
        str(app),
    ]
    print(f"Merging web-flash image -> {out}")
    subprocess.run(cmd, check=True)


env.AddPostAction("$BUILD_DIR/${PROGNAME}.bin", merge_bin)
