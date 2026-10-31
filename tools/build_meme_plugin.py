"""Build the two-entry ZIP accepted by PluginArchive, reproducibly."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
source = root / 'examples/meme-sound-plugin'
target = root / 'examples/meme-sound-plugin.zip'
with ZipFile(target, 'w') as archive:
    for name in ('plugin.json', 'plugin.py'):
        entry = ZipInfo(name, (2024, 1, 1, 0, 0, 0))
        entry.compress_type = ZIP_DEFLATED
        entry.external_attr = 0o100644 << 16
        archive.writestr(entry, (source / name).read_bytes())
print(f'Built {target.relative_to(root)} ({target.stat().st_size} bytes)')
