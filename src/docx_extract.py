"""DOCX extraction via Apache Tika (issue #4)."""

from __future__ import annotations

from pathlib import Path
from typing import Any


def extract_docx_text(path: str | Path, *, tika_server_url: str | None = None) -> str:
    """Extract plain text from a .docx file using tika-python if available."""
    path = Path(path)
    if path.suffix.lower() not in {".docx", ".doc"}:
        raise ValueError(f"expected .docx, got {path.suffix}")
    try:
        from tika import parser  # type: ignore
    except ImportError as e:
        raise ImportError(
            "python-tika is required for DOCX extraction. Install with: pip install tika"
        ) from e
    kwargs: dict[str, Any] = {}
    if tika_server_url:
        kwargs["serverEndpoint"] = tika_server_url
    parsed = parser.from_file(str(path), **kwargs)
    return (parsed or {}).get("content") or ""
