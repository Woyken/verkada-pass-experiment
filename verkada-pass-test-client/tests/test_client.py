from __future__ import annotations

import json
import unittest

import httpx

from verkada_pass_test_client.client import VerkadaPassClient
from verkada_pass_test_client.models import SessionState


class ClientPolicyEndpointsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.session = SessionState(
            organization_id="org-123",
            user_id="user-123",
            user_token="token-123",
            email="user@example.com",
            shard_domain="example.command.verkada.com",
        )

    def test_get_user_profiles_accepts_single_object_response(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.method, "GET")
            self.assertEqual(request.url.path, "/user/org-123")
            return httpx.Response(
                200,
                json={
                    "userId": "user-123",
                    "accessMethods": {"entity-1": {"MOBILE": False, "BLUETOOTH": True}},
                },
            )

        client = self._build_client(handler)
        try:
            profiles = client.get_user_profiles(self.session)
        finally:
            client.close()

        self.assertEqual(len(profiles), 1)
        self.assertEqual(profiles[0]["userId"], "user-123")
        self.assertEqual(profiles[0]["accessMethods"]["entity-1"]["MOBILE"], False)

    def test_get_organization_config_maps_param_values(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.method, "POST")
            self.assertEqual(request.url.path, "/organization/config/get")
            self.assertEqual(json.loads(request.content.decode("utf-8")), {"organizationId": "org-123"})
            return httpx.Response(
                200,
                json={
                    "organizationConfigParams": [
                        {"paramName": "api-unlock-enabled", "paramValue": "false"},
                        {"paramName": "ble-unlock-enabled", "paramValue": "true"},
                    ]
                },
            )

        client = self._build_client(handler)
        try:
            config = client.get_organization_config(self.session)
        finally:
            client.close()

        self.assertEqual(
            config,
            {
                "api-unlock-enabled": "false",
                "ble-unlock-enabled": "true",
            },
        )

    @staticmethod
    def _build_client(handler) -> VerkadaPassClient:
        client = VerkadaPassClient()
        client.close()
        client._client = httpx.Client(transport=httpx.MockTransport(handler), follow_redirects=True)
        return client
