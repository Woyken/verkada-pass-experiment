from __future__ import annotations

from typing import Any
from urllib.parse import urlsplit, urlunsplit

import httpx

from .models import DoorRecord, MagicLinkContext, SessionState, UnlockResult

EMAIL_MAGIC_LINK_URL = "https://vauth.command.verkada.com/auth/magic"
LOGIN_URL = "https://vprovision.command.verkada.com/user/login"
ACCESS_POINTS_URL = "https://vcerberus.command.verkada.com/access/v2/user/pass/unlockables/1"
REMOTE_UNLOCK_URL_TEMPLATE = "https://vcerberus.command.verkada.com/access/v2/user/virtual_device/{access_point_id}/unlock"


def rewrite_command_host(url: str, shard_domain: str | None) -> str:
    if not shard_domain:
        return url

    parsed = urlsplit(url)
    if "command.verkada.com" not in parsed.netloc:
        return url

    rewritten_netloc = parsed.netloc.replace("command.verkada.com", shard_domain)
    return urlunsplit((parsed.scheme, rewritten_netloc, parsed.path, parsed.query, parsed.fragment))


class VerkadaPassClient:
    def __init__(self, *, timeout_seconds: float = 30.0) -> None:
        self._client = httpx.Client(timeout=timeout_seconds, follow_redirects=True)

    def __enter__(self) -> "VerkadaPassClient":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    def close(self) -> None:
        self._client.close()

    def request_magic_link(self, email: str, org_short_name: str | None = None) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "email": email,
            "tokenScopeTypes": ["PASS_APP"],
        }
        if org_short_name:
            payload["organizationShortName"] = org_short_name

        response = self._client.post(EMAIL_MAGIC_LINK_URL, json=payload)
        self._raise_for_status(response, "Requesting a magic link")
        return self._maybe_json(response)

    def redeem_magic_link(self, link: MagicLinkContext) -> SessionState:
        payload: dict[str, Any] = {
            "appNotificationToken": None,
            "notificationPermissions": None,
            "email": link.user_email,
            "magicToken": link.magic_token,
            "tokenScopeTypes": ["PASS_APP"],
        }
        if link.org_short_name:
            payload["orgShortName"] = link.org_short_name
        if link.entity_id:
            payload["entityId"] = link.entity_id

        response = self._client.post(LOGIN_URL, json=payload)
        self._raise_for_status(response, "Redeeming the magic link")
        data = self._expect_json_object(response, "login response")
        return SessionState.from_login_response(data, org_short_name=link.org_short_name)

    def list_doors(self, session: SessionState) -> list[DoorRecord]:
        url = rewrite_command_host(ACCESS_POINTS_URL, session.shard_domain)
        response = self._client.post(
            url,
            headers=session.auth_headers(),
            json={"organizationId": session.organization_id},
        )
        self._raise_for_status(response, "Fetching doors")
        data = self._expect_json_object(response, "doors response")

        unlockables = data.get("unlockables") or data.get("accessPoints")
        if not isinstance(unlockables, dict):
            raise RuntimeError("The doors response did not include an unlockables object.")

        doors = unlockables.get("doors")
        if not isinstance(doors, list):
            raise RuntimeError("The doors response did not include a doors array.")

        return [DoorRecord.from_api(item) for item in doors if isinstance(item, dict)]

    def unlock_door(self, session: SessionState, access_point_id: str, unlock_method: str = "mobile") -> UnlockResult:
        url = rewrite_command_host(
            REMOTE_UNLOCK_URL_TEMPLATE.format(access_point_id=access_point_id),
            session.shard_domain,
        )
        response = self._client.post(
            url,
            headers=session.auth_headers(),
            json={"unlockMethod": unlock_method},
        )
        self._raise_for_status(response, "Unlocking the door")
        data = self._expect_json_object(response, "unlock response")
        duration = data.get("duration")
        return UnlockResult(duration=float(duration) if duration is not None else None, raw=data)

    @staticmethod
    def _maybe_json(response: httpx.Response) -> dict[str, Any]:
        if not response.content:
            return {}
        try:
            data = response.json()
        except ValueError:
            return {}
        return data if isinstance(data, dict) else {}

    @staticmethod
    def _expect_json_object(response: httpx.Response, label: str) -> dict[str, Any]:
        try:
            data = response.json()
        except ValueError as exc:
            raise RuntimeError(f"The {label} was not valid JSON.") from exc

        if not isinstance(data, dict):
            raise RuntimeError(f"The {label} was not a JSON object.")
        return data

    @staticmethod
    def _raise_for_status(response: httpx.Response, action: str) -> None:
        try:
            response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            detail = response.text.strip()
            suffix = f"\n{detail}" if detail else ""
            raise RuntimeError(
                f"{action} failed with HTTP {response.status_code} {response.reason_phrase}.{suffix}"
            ) from exc
