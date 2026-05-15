from __future__ import annotations

import argparse
from pathlib import Path
import sys
from typing import Sequence

from .client import VerkadaPassClient
from .config import AppConfig, delete_session, load_config, load_session, resolve_session_path, save_session
from .models import DoorRecord, MagicLinkContext, SessionState


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="verkada-pass-test-client",
        description="Interactive Verkada Pass test client for magic-link login, door listing, and remote unlock.",
    )
    parser.add_argument(
        "--config",
        type=Path,
        default=project_root() / "config.toml",
        help="Path to the TOML config file. Defaults to ./config.toml.",
    )
    parser.add_argument(
        "--force-login",
        action="store_true",
        help="Ignore any saved session and go through the magic-link flow again.",
    )
    parser.add_argument(
        "--magic-link-url",
        help="Provide the full magic-link URL directly instead of reading it from config.toml or prompting.",
    )
    parser.add_argument(
        "--request-link-only",
        action="store_true",
        help="Send the magic-link email request and exit without redeeming it.",
    )
    parser.add_argument(
        "--list-only",
        action="store_true",
        help="List doors after login and exit without unlocking one.",
    )
    parser.add_argument(
        "--logout",
        action="store_true",
        help="Delete the saved session file and exit.",
    )
    parser.add_argument(
        "--unlock-method",
        help='Override the remote unlock method. Defaults to the config value or "mobile".',
    )
    return parser


def project_root() -> Path:
    return Path(__file__).resolve().parents[2]


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    config_path = args.config.resolve()
    try:
        config = load_config(config_path)
        session_path = resolve_session_path(config_path, config)

        if args.logout:
            delete_session(session_path)
            print(f"Removed saved session: {session_path}")
            return 0

        with VerkadaPassClient(timeout_seconds=config.timeout_seconds) as client:
            if args.request_link_only:
                email, org_short_name = prompt_login_identity(config)
                client.request_magic_link(email, org_short_name)
                print("Magic link requested. Copy the URL from your email and then run the app again.")
                return 0

            session = ensure_session(
                client=client,
                config=config,
                session_path=session_path,
                force_login=args.force_login,
                magic_link_override=args.magic_link_url,
            )

            doors = client.list_doors(session)
            if not doors:
                print("No doors were returned for this account.")
                return 0

            print("")
            print("Available doors:")
            print_door_list(doors)

            if args.list_only:
                return 0

            selected_door = choose_door(doors)
            unlock_method = args.unlock_method or config.unlock_method
            result = client.unlock_door(session, selected_door.access_point_id, unlock_method)

            print("")
            print(f"Unlocked: {selected_door.name}")
            print(f"Unlock method: {unlock_method}")
            if result.duration is not None:
                print(f"Duration: {result.duration:.2f} seconds")

        return 0
    except (RuntimeError, ValueError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nCancelled.", file=sys.stderr)
        return 130


def ensure_session(
    *,
    client: VerkadaPassClient,
    config: AppConfig,
    session_path: Path,
    force_login: bool,
    magic_link_override: str | None,
) -> SessionState:
    if not force_login:
        saved_session = load_session(session_path)
        if saved_session and prompt_yes_no(f"Reuse saved session from {session_path}?", default=True):
            return saved_session

    email, org_short_name = prompt_login_identity(config)
    magic_link_url = magic_link_override or config.magic_link_url

    if not magic_link_url:
        should_request = config.request_magic_link_first
        if should_request:
            print("Requesting a fresh magic link...")
            client.request_magic_link(email, org_short_name)
            print("Magic link requested. Paste the full URL from your email below.")

        magic_link_url = prompt_non_empty("Magic link URL")

    link = MagicLinkContext.from_url(magic_link_url)
    session = client.redeem_magic_link(link)
    save_session(session_path, session)
    print(f"Saved session to {session_path}")
    return session


def prompt_login_identity(config: AppConfig) -> tuple[str, str | None]:
    email = config.email or prompt_non_empty("Email")
    org_short_name = config.org_short_name
    if org_short_name is None:
        value = input("Org short name (optional): ").strip()
        org_short_name = value or None
    return email, org_short_name


def choose_door(doors: list[DoorRecord]) -> DoorRecord:
    while True:
        raw_value = prompt_non_empty("Select door number")
        try:
            index = int(raw_value)
        except ValueError:
            print("Please enter a number from the list.")
            continue

        if 1 <= index <= len(doors):
            return doors[index - 1]

        print("Door number is out of range.")


def print_door_list(doors: list[DoorRecord]) -> None:
    for index, door in enumerate(doors, start=1):
        details: list[str] = []
        if door.access_controller_id:
            details.append(f"controller={door.access_controller_id}")
        if door.floor_id:
            details.append(f"floor={door.floor_id}")
        suffix = f" ({', '.join(details)})" if details else ""
        print(f"{index}. {door.name} [{door.access_point_id}]{suffix}")


def prompt_non_empty(label: str) -> str:
    while True:
        value = input(f"{label}: ").strip()
        if value:
            return value
        print(f"{label} cannot be empty.")


def prompt_yes_no(message: str, *, default: bool) -> bool:
    suffix = "[Y/n]" if default else "[y/N]"
    value = input(f"{message} {suffix} ").strip().lower()
    if not value:
        return default
    if value in {"y", "yes"}:
        return True
    if value in {"n", "no"}:
        return False
    print("Please answer yes or no.")
    return prompt_yes_no(message, default=default)
