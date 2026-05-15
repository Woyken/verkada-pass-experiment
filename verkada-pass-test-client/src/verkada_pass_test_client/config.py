from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any
import tomllib
import tomli_w

from .models import SessionState


@dataclass(slots=True)
class AppConfig:
    email: str | None = None
    org_short_name: str | None = None
    magic_link_url: str | None = None
    unlock_method: str = "mobile"
    request_magic_link_first: bool = True
    session_file: str = ".verkada-pass-session.toml"
    timeout_seconds: float = 30.0

    @classmethod
    def from_mapping(cls, data: dict[str, Any]) -> "AppConfig":
        return cls(
            email=_string_value(data.get("email")),
            org_short_name=_string_value(data.get("org_short_name")),
            magic_link_url=_string_value(data.get("magic_link_url")),
            unlock_method=_string_value(data.get("unlock_method")) or "mobile",
            request_magic_link_first=bool(data.get("request_magic_link_first", True)),
            session_file=_string_value(data.get("session_file")) or ".verkada-pass-session.toml",
            timeout_seconds=float(data.get("timeout_seconds", 30.0)),
        )


def _string_value(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def load_config(path: Path) -> AppConfig:
    if not path.exists():
        return AppConfig()

    with path.open("rb") as handle:
        data = tomllib.load(handle)

    return AppConfig.from_mapping(data)


def resolve_session_path(config_path: Path, config: AppConfig) -> Path:
    session_path = Path(config.session_file)
    if session_path.is_absolute():
        return session_path
    return config_path.parent / session_path


def load_session(path: Path) -> SessionState | None:
    if not path.exists():
        return None

    with path.open("rb") as handle:
        data = tomllib.load(handle)

    session = data.get("session")
    if not isinstance(session, dict):
        raise ValueError(f"Session file at {path} is missing a [session] table.")

    return SessionState.from_toml_dict(session)


def save_session(path: Path, session: SessionState) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as handle:
        tomli_w.dump({"session": session.to_toml_dict()}, handle)


def delete_session(path: Path) -> None:
    if path.exists():
        path.unlink()
