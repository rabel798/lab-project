import unittest
import json
import sqlite3
import os
import tempfile
import app


class BankServerTestCase(unittest.TestCase):
    """
    Comprehensive Unit Test Suite for EmPay Central Bank Backend (app.py).
    Covers User Profiles, Emergency Fund Allocation, Batch Sync,
    Anti-Double-Spending Invariants, Outage Simulation, and Demo Resets.
    """

    def setUp(self):
        # Use an isolated temporary database for test determinism
        self.db_fd, self.temp_db_path = tempfile.mkstemp(suffix=".db")
        app.DB_PATH = self.temp_db_path
        app.app.config['TESTING'] = True
        self.client = app.app.test_client()

        # Initialize pristine schema & seed data
        app.init_db()
        conn = sqlite3.connect(self.temp_db_path)
        conn.execute("DELETE FROM users")
        conn.execute("DELETE FROM transactions")
        conn.execute("""
            INSERT INTO users (id, name, phone, balance, emergency_balance)
            VALUES (1, 'Rahul Sharma', '9876543210', 2500.0, 500.0)
        """)
        conn.commit()
        conn.close()

    def tearDown(self):
        os.close(self.db_fd)
        if os.path.exists(self.temp_db_path):
            os.remove(self.temp_db_path)

    def test_01_get_user_profile(self):
        """Phase 2 & 3: Verifies default user profile and balances."""
        response = self.client.get("/api/user")
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data.decode())
        self.assertEqual(data["name"], "Rahul Sharma")
        self.assertEqual(data["phone"], "9876543210")
        self.assertEqual(data["balance"], 2500.0)
        self.assertEqual(data["emergency_balance"], 500.0)

    def test_02_transfer_to_emergency_success(self):
        """Phase 3: Allocating funds from online to offline emergency reserve."""
        payload = {"amount": 200.0}
        response = self.client.post(
            "/api/user/transfer-to-emergency",
            data=json.dumps(payload),
            content_type="application/json"
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data.decode())
        self.assertTrue(data["success"])
        self.assertEqual(data["balance"], 2300.0)
        self.assertEqual(data["emergency_balance"], 700.0)

    def test_03_transfer_to_emergency_insufficient_balance(self):
        """Phase 3: Attempting to allocate more funds than online balance."""
        payload = {"amount": 3000.0}  # Available is 2500.0
        response = self.client.post(
            "/api/user/transfer-to-emergency",
            data=json.dumps(payload),
            content_type="application/json"
        )
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.data.decode())
        self.assertFalse(data["success"])
        self.assertIn("Insufficient", data["message"])

    def test_04_transfer_to_emergency_invalid_amounts(self):
        """Phase 3: Boundary validation - negative and zero transfers."""
        for invalid_amt in [0, -50.0, -0.01]:
            payload = {"amount": invalid_amt}
            response = self.client.post(
                "/api/user/transfer-to-emergency",
                data=json.dumps(payload),
                content_type="application/json"
            )
            self.assertEqual(response.status_code, 400)

    def test_05_liquidity_invariant_preservation(self):
        """Total System Liquidity Invariant: balance + emergency_balance remains constant during transfers."""
        initial_resp = self.client.get("/api/user")
        initial_data = json.loads(initial_resp.data.decode())
        initial_total = initial_data["balance"] + initial_data["emergency_balance"]

        # Reserve ₹350
        self.client.post(
            "/api/user/transfer-to-emergency",
            data=json.dumps({"amount": 350.0}),
            content_type="application/json"
        )

        after_resp = self.client.get("/api/user")
        after_data = json.loads(after_resp.data.decode())
        after_total = after_data["balance"] + after_data["emergency_balance"]

        self.assertAlmostEqual(initial_total, after_total)

    def test_06_batch_offline_transactions_sync(self):
        """Phase 5 & 6: Reconciles offline P2P Bluetooth transactions with Central Bank."""
        offline_txs = [
            {
                "tx_id": "TX-TEST-BT-001",
                "payee": "Apex City Care Pharmacy",
                "amount": 25.0,
                "note": "Emergency Antibiotics"
            },
            {
                "tx_id": "TX-TEST-BT-002",
                "payee": "Daily Fresh Supermarket",
                "amount": 40.0,
                "note": "Bottled Water & Rations"
            }
        ]
        sync_payload = {"transactions": offline_txs}
        response = self.client.post(
            "/api/transactions/sync",
            data=json.dumps(sync_payload),
            content_type="application/json"
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data.decode())
        self.assertEqual(data["status"], "success")

        # Verify transactions were inserted into bank ledger
        conn = sqlite3.connect(self.temp_db_path)
        conn.row_factory = sqlite3.Row
        saved_txs = conn.execute("SELECT * FROM transactions WHERE tx_id LIKE 'TX-TEST-BT-%'").fetchall()
        self.assertEqual(len(saved_txs), 2)
        tx_map = {t["tx_id"]: t for t in saved_txs}
        self.assertEqual(tx_map["TX-TEST-BT-001"]["amount"], 25.0)
        self.assertEqual(tx_map["TX-TEST-BT-001"]["status"], "completed")
        self.assertEqual(tx_map["TX-TEST-BT-002"]["amount"], 40.0)
        conn.close()

    def test_07_sync_duplicate_idempotency(self):
        """Anti-Replay / Idempotency: Re-syncing the same transaction ID does not double-count."""
        tx_payload = {
            "transactions": [
                {
                    "tx_id": "TX-IDEMPOTENT-001",
                    "payee": "Metro Hospital",
                    "amount": 100.0,
                    "note": "First Aid Kit"
                }
            ]
        }
        # First sync
        resp1 = self.client.post("/api/transactions/sync", data=json.dumps(tx_payload), content_type="application/json")
        self.assertEqual(resp1.status_code, 200)

        # Duplicate sync with identical tx_id
        resp2 = self.client.post("/api/transactions/sync", data=json.dumps(tx_payload), content_type="application/json")
        self.assertEqual(resp2.status_code, 200)

        conn = sqlite3.connect(self.temp_db_path)
        count = conn.execute("SELECT COUNT(*) FROM transactions WHERE tx_id = 'TX-IDEMPOTENT-001'").fetchone()[0]
        self.assertEqual(count, 1)
        conn.close()

    def test_08_outage_simulation_status_and_toggle(self):
        """Simulates disaster network blackout toggles."""
        # Query current network status
        resp = self.client.get("/api/system/network-status")
        self.assertEqual(resp.status_code, 200)

        # Toggle network status
        toggle_resp = self.client.post("/api/system/toggle-network")
        self.assertEqual(toggle_resp.status_code, 200)
        toggle_data = json.loads(toggle_resp.data.decode())
        self.assertIn("status", toggle_data)
        self.assertIn(toggle_data["status"], ["online", "offline"])

    def test_09_users_list_liquidity_api(self):
        """Bank Dashboard /bank Liquidity overview API."""
        resp = self.client.get("/api/users")
        self.assertEqual(resp.status_code, 200)
        users = json.loads(resp.data.decode())
        self.assertIsInstance(users, list)
        self.assertGreaterEqual(len(users), 1)
        self.assertEqual(users[0]["name"], "Rahul Sharma")
        total_liquidity = sum(u["balance"] + u["emergency_balance"] for u in users)
        self.assertGreater(total_liquidity, 0)

    def test_10_reset_demo_restores_pristine_state(self):
        """Bank Dashboard reset button returns ledger to official demo baseline (₹500 / ₹10)."""
        resp = self.client.post("/api/bank/reset-demo")
        self.assertEqual(resp.status_code, 200)
        data = json.loads(resp.data.decode())
        self.assertTrue(data["success"])

        # Check default user balance is restored to rubric standard: ₹500.00 Online, ₹10.00 Emergency
        user_resp = self.client.get("/api/user")
        user_data = json.loads(user_resp.data.decode())
        self.assertEqual(user_data["balance"], 500.0)
        self.assertEqual(user_data["emergency_balance"], 10.0)


if __name__ == "__main__":
    unittest.main()
