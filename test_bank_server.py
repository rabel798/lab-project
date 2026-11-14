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

        # Verify emergency balance was permanently deducted and not reset
        conn = sqlite3.connect(self.temp_db_path)
        conn.row_factory = sqlite3.Row
        user = conn.execute("SELECT * FROM users WHERE id = 1").fetchone()
        self.assertEqual(data["reconciled_count"], 2)
        # In setUp: emergency_balance starts at 500.0. Spent 25 + 40 = 65.0 -> 435.0
        self.assertEqual(user["emergency_balance"], 435.0)
        self.assertEqual(data["emergency_balance"], 435.0)
        conn.close()

        # Verify subsequent GET /api/user also retains the deducted balance
        check_user = json.loads(self.client.get("/api/user").data.decode())
        self.assertEqual(check_user["emergency_balance"], 435.0)

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

    def test_11_offline_sync_deduction_and_overflow(self):
        """Phase 6: Verifies that syncing offline spending permanently deducts from emergency balance and overflows to online balance if needed."""
        # Reset to demo baseline: ₹500.00 online, ₹10.00 emergency
        self.client.post("/api/bank/reset-demo")

        # Spend ₹15.00 offline: ₹10.00 from emergency, remaining ₹5.00 from online balance
        tx_payload = {
            "transactions": [
                {
                    "tx_id": "TX-OVERFLOW-001",
                    "payee": "Emergency Relief Camp",
                    "amount": 15.0,
                    "direction": "SENT",
                    "note": "Rations & Medical Supplies"
                }
            ]
        }
        sync_resp = self.client.post("/api/transactions/sync", data=json.dumps(tx_payload), content_type="application/json")
        self.assertEqual(sync_resp.status_code, 200)
        data = json.loads(sync_resp.data.decode())

        self.assertEqual(data["emergency_balance"], 0.0)
        self.assertEqual(data["balance"], 495.0)

        # Confirm persisted in DB
        user_resp = self.client.get("/api/user")
        user_data = json.loads(user_resp.data.decode())
        self.assertEqual(user_data["emergency_balance"], 0.0)
        self.assertEqual(user_data["balance"], 495.0)

    def test_12_self_transfer_does_not_deduct_balance(self):
        """Self-Transfer Protection: Transferring money to oneself across devices does not decrease central bank balance."""
        self.client.post("/api/bank/reset-demo")
        initial_user = json.loads(self.client.get("/api/user").data.decode())
        initial_bal = initial_user["balance"]
        initial_emg = initial_user["emergency_balance"]

        # User 1 sends money to themselves (Payee is Rahul Sharma / same user_id)
        self_tx_payload = {
            "transactions": [
                {
                    "tx_id": "TX-SELF-001",
                    "sender_id": 1,
                    "receiver_id": 1,
                    "payee": "Rahul Sharma",
                    "amount": 5.0,
                    "direction": "SENT",
                    "note": "Internal self-transfer between devices"
                }
            ]
        }
        resp = self.client.post("/api/transactions/sync", data=json.dumps(self_tx_payload), content_type="application/json")
        self.assertEqual(resp.status_code, 200)
        data = json.loads(resp.data.decode())

        # Balance must NOT be deducted
        self.assertEqual(data["balance"], initial_bal)
        self.assertEqual(data["emergency_balance"], initial_emg)

        persisted = json.loads(self.client.get("/api/user").data.decode())
        self.assertEqual(persisted["emergency_balance"], initial_emg)

    def test_13_multi_user_profiles_and_distinct_ids(self):
        """Multi-Device Testing: Multiple unique user accounts exist with separate IDs and phone numbers."""
        app.init_db()
        resp = self.client.get("/api/users")
        self.assertEqual(resp.status_code, 200)
        users = json.loads(resp.data.decode())
        self.assertGreaterEqual(len(users), 4)

        user_names = [u["name"] for u in users]
        self.assertIn("Rahul Sharma", user_names)
        self.assertIn("Sukhpal Singh", user_names)
        self.assertIn("Priya Sharma", user_names)

        # Ensure all IDs and phone numbers are unique
        user_ids = [u["id"] for u in users]
        user_phones = [u["phone"] for u in users]
        self.assertEqual(len(user_ids), len(set(user_ids)))
        self.assertEqual(len(user_phones), len(set(user_phones)))

    def test_14_p2p_transfer_between_distinct_users_credits_and_debits_correctly(self):
        """P2P Transfer: User A (95 offline) sends 5 to User B (10 offline) -> User A has 90, User B has 15."""
        app.init_db()
        conn = app.get_db()
        sukhpal_id = conn.execute("SELECT id FROM users WHERE phone = '9811122233'").fetchone()["id"]
        priya_id = conn.execute("SELECT id FROM users WHERE phone = '8765432109'").fetchone()["id"]
        conn.execute("UPDATE users SET emergency_balance = 95.0 WHERE id = ?", (sukhpal_id,))
        conn.execute("UPDATE users SET emergency_balance = 10.0 WHERE id = ?", (priya_id,))
        conn.commit()
        conn.close()

        # Sukhpal sends 5 to Priya
        tx_payload = {
            "user_id": sukhpal_id,
            "transactions": [
                {
                    "tx_id": "TX-P2P-SUKHPAL-TO-PRIYA-001",
                    "sender_id": sukhpal_id,
                    "receiver_id": priya_id,
                    "payee": "Priya Sharma",
                    "amount": 5.0,
                    "direction": "SENT",
                    "note": "P2P Bluetooth transfer"
                }
            ]
        }
        resp = self.client.post("/api/transactions/sync", data=json.dumps(tx_payload), content_type="application/json")
        self.assertEqual(resp.status_code, 200)
        data = json.loads(resp.data.decode())

        # Sukhpal's sync response must show 90.0 (95 - 5)
        self.assertEqual(data["emergency_balance"], 90.0)

        # Check Priya's balance from the bank directly: must be 15.0 (10 + 5)
        priya_resp = self.client.get(f"/api/users/{priya_id}")
        self.assertEqual(priya_resp.status_code, 200)
        priya_data = json.loads(priya_resp.data.decode())
        self.assertEqual(priya_data["emergency_balance"], 15.0)

        # When Priya syncs with Central Bank, she must receive 15.0 (NOT 90.0)
        priya_sync = self.client.post("/api/transactions/sync", data=json.dumps({"user_id": priya_id, "transactions": []}), content_type="application/json")
        self.assertEqual(priya_sync.status_code, 200)
        priya_sync_data = json.loads(priya_sync.data.decode())
        self.assertEqual(priya_sync_data["emergency_balance"], 15.0)


if __name__ == "__main__":
    unittest.main()
