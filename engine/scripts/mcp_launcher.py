from __future__ import annotations

import os
import runpy
import sys
from pathlib import Path

ENGINE_ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = ENGINE_ROOT / "src"
SERVER_PATH = SRC_ROOT / "mcp_server.py"


def main() -> int:
    os.chdir(ENGINE_ROOT)
    os.environ.setdefault("PYTHONUNBUFFERED", "1")
    os.environ.setdefault("UV_CACHE_DIR", str(ENGINE_ROOT / ".uv-cache"))
    sys.path.insert(0, str(SRC_ROOT))
    runpy.run_path(str(SERVER_PATH), run_name="__main__")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
