"""Build the actual native library and exercise its JNI ABI on a desktop JVM."""

from pathlib import Path
import os
import shutil
import subprocess


def java_tool(name: str) -> str:
    executable = name + (".exe" if os.name == "nt" else "")
    if java_home := os.environ.get("JAVA_HOME"):
        candidate = Path(java_home) / "bin" / executable
        if candidate.is_file():
            return str(candidate)
    if found := shutil.which(executable):
        return found
    raise SystemExit(f"JDK required: set JAVA_HOME or put {executable} on PATH")


def main() -> None:
    audio = Path(__file__).resolve().parents[1]
    repository = audio.parents[1]
    target = Path(os.environ.get("CARGO_TARGET_DIR", audio / "target"))
    if not target.is_absolute():
        target = repository / target
    subprocess.run(
        ["cargo", "build", "--manifest-path", str(audio / "Cargo.toml"), "--release", "--locked", "--features", "jni-bridge"],
        cwd=repository,
        check=True,
    )
    classes = target / "jni-test-classes"
    classes.mkdir(parents=True, exist_ok=True)
    fixtures = sorted((audio / "tests/java").rglob("*.java"))
    subprocess.run([java_tool("javac"), "-d", str(classes), *map(str, fixtures)], check=True)
    subprocess.run(
        [java_tool("java"), f"-Djava.library.path={target / 'release'}", "-cp", str(classes), "com.github.mytv.bv.player.audio.NativePcmGain"],
        check=True,
    )


if __name__ == "__main__":
    main()
