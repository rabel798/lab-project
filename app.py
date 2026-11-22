from flask import Flask, render_template, send_from_directory, request, jsonify
import sqlite3
import os
import json
from datetime import datetime
import random

app = Flask(__name__, static_folder="templates/assets", static_url_path="/assets")

DB_PATH = os.path.join(app.root_path, "database.db")
STATUS_FILE = os.path.join(app.root_path, "connection-status.json")


def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    conn = get_db()
    cursor = conn.cursor()

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            phone TEXT UNIQUE NOT NULL,
            balance REAL DEFAULT 2500.0,
            emergency_balance REAL DEFAULT 500.0,
            keypair TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS merchants (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            phone TEXT,
            category TEXT,
            is_essential INTEGER DEFAULT 1,
            distance REAL DEFAULT 2.0,
            address TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS transactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            tx_id TEXT,
            sender_id INTEGER,
            receiver_id INTEGER,
            payee TEXT,
            amount REAL NOT NULL,
            status TEXT DEFAULT 'completed',
            transaction_type TEXT DEFAULT 'online',
            channel TEXT DEFAULT 'Bluetooth',
            note TEXT,
            token TEXT,
            is_offline INTEGER DEFAULT 0,
            method TEXT DEFAULT 'UPI',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)

    # Ensure primary user Rahul Sharma exists at id 1 with official hackathon balances (500 online, 100 offline)
    cursor.execute("SELECT * FROM users WHERE id = 1")
    user1 = cursor.fetchone()
    if user1:
        cursor.execute("""
            UPDATE users SET name = 'Rahul Sharma', phone = '9876543210', balance = 500.0, emergency_balance = 100.0
            WHERE id = 1
        """)
    else:
        cursor.execute("""
            INSERT INTO users (id, name, phone, balance, emergency_balance)
            VALUES (1, 'Rahul Sharma', '9876543210', 500.0, 100.0)
        """)

    # Clean up legacy mock/superhero users
    cursor.execute("DELETE FROM users WHERE name IN ('Dr. Doom', 'Sunsan Storm', 'Pavitra Prabhakar', 'Mr. Sukhpal')")

    # Ensure genuine demo peers exist with balances (500 online, 100 offline)
    demo_users = [
        ("Sukhpal Singh", "9811122233", 500.0, 100.0),
        ("Priya Sharma", "8765432109", 500.0, 100.0),
        ("Dr. Rohan Verma", "7654321098", 500.0, 100.0),
        ("Ananya Patel", "6543210987", 500.0, 100.0)
    ]
    for d_name, d_phone, d_bal, d_emg in demo_users:
        cursor.execute("SELECT * FROM users WHERE phone = ?", (d_phone,))
        existing = cursor.fetchone()
        if existing:
            cursor.execute("""
                UPDATE users SET name = ?, balance = ?, emergency_balance = ? WHERE phone = ?
            """, (d_name, d_bal, d_emg, d_phone))
        else:
            cursor.execute("""
                INSERT INTO users (name, phone, balance, emergency_balance)
                VALUES (?, ?, ?, ?)
            """, (d_name, d_phone, d_bal, d_emg))

    # Clean up legacy mock/superhero merchants
    cursor.execute("""
        DELETE FROM merchants WHERE name IN (
            'S.H.I.E.L.D Daycare',
            'Spidey Pizza Service',
            'Widow Spa',
            'Panther Jewellers',
            'Electrician(God of Hammers) Services'
        )
    """)

    # Seed genuine official merchants if empty
    cursor.execute("SELECT COUNT(*) as cnt FROM merchants")
    if cursor.fetchone()["cnt"] == 0:
        default_merchants = [
            ("Apex City Care Pharmacy", "9876543211", "Healthcare", 1, 1.2, "Sector 17, Main Market"),
            ("Daily Fresh Supermarket", "9876543212", "Groceries", 1, 0.8, "Central Avenue, Block 4"),
            ("LifeLine Emergency Medicos", "9876543213", "Healthcare", 1, 1.5, "Civic Hospital Road"),
            ("National Book & Stationery Depot", "9876543214", "Retail", 0, 2.4, "Metro Complex, Shop 12"),
            ("City QuickFix Electrical Services", "9876543215", "Emergency Services", 1, 1.8, "Utility Hub, Cross Road 3")
        ]
        cursor.executemany("""
            INSERT INTO merchants (name, phone, category, is_essential, distance, address)
            VALUES (?, ?, ?, ?, ?, ?)
        """, default_merchants)

    # Ensure Apex City Care Pharmacy is present
    cursor.execute("SELECT id FROM merchants WHERE name = 'Apex City Care Pharmacy'")
    if not cursor.fetchone():
        cursor.execute("""
            INSERT INTO merchants (name, phone, category, is_essential, distance, address)
            VALUES ('Apex City Care Pharmacy', '9876543211', 'Healthcare', 1, 1.2, 'Sector 17, Main Market')
        """)

    # Clear transaction history
    cursor.execute("DELETE FROM transactions")

    conn.commit()
    conn.close()


init_db()


def get_network_status():
    if os.path.exists(STATUS_FILE):
        try:
            with open(STATUS_FILE, "r") as f:
                data = json.load(f)
                return data.get("status", "online")
        except Exception:
            return "online"
    return "online"


def set_network_status(status):
    with open(STATUS_FILE, "w") as f:
        json.dump({"status": status}, f)


# ==========================================
# PAGE ROUTES
# ==========================================

@app.route("/")
def main_portfolio():
    return render_template("main.html")  # Portfolio untouched


@app.route("/bank")
def bank_dashboard():
    return render_template("bank.html")


@app.route("/emergencypay")
def emergency_pay():
    return render_template("emergencypay.html")


@app.route("/bluetooth")
@app.route("/bluetooth-payment")
def bluetooth():
    return render_template("bluetooth.html")


@app.route("/pay")
@app.route("/direct-transfer")
@app.route("/direct-transfer/<phone>")
def pay(phone=None):
    return render_template("pay.html", phone=phone)


@app.route("/pin")
def pin():
    return render_template("pin.html")


@app.route("/scan")
@app.route("/qr-scan")
def scan():
    return render_template("scan.html")


@app.route("/receive")
def receive():
    return render_template("receive.html")


@app.route("/merchants")
def merchants():
    return render_template("merchants.html")


@app.route("/history")
@app.route("/transactions")
def history():
    return render_template("history.html")


@app.route("/receipt")
def receipt():
    return render_template("receipt.html")


@app.route("/profile")
def profile():
    return render_template("profile.html")


@app.route("/assets/<path:filename>")
def serve_assets(filename):
    return send_from_directory(os.path.join(app.root_path, "templates", "assets"), filename)


# ==========================================
# REST API ENDPOINTS & USER RESOLUTION
# ==========================================

DEMO_PHONE_MAP = {
    1: "9876543210",  # Rahul Sharma
    2: "9811122233",  # Sukhpal Singh
    3: "8765432109",  # Priya Sharma
    4: "7654321098",  # Dr. Rohan Verma
    5: "6543210987",  # Ananya Patel
}

def resolve_user_id(conn, user_id):
    """
    Resolves an integer user ID or demo index to a valid database user ID.
    If an auto-incremented database has different primary keys,
    falls back to phone number lookup via DEMO_PHONE_MAP.
    """
    if user_id is None:
        return 1
    try:
        uid = int(user_id)
    except (ValueError, TypeError):
        # Maybe it's a phone string
        p_row = conn.execute("SELECT id FROM users WHERE phone = ?", (str(user_id).strip(),)).fetchone()
        return p_row["id"] if p_row else None

    # Check exact ID first
    row = conn.execute("SELECT id FROM users WHERE id = ?", (uid,)).fetchone()
    if row:
        return row["id"]
    # Fallback to phone mapping for demo users
    if uid in DEMO_PHONE_MAP:
        p_row = conn.execute("SELECT id FROM users WHERE phone = ?", (DEMO_PHONE_MAP[uid],)).fetchone()
        if p_row:
            return p_row["id"]
    return uid


@app.route("/api/user", methods=["GET"])
def api_get_user():
    conn = get_db()
    phone = request.args.get("phone")
    user_id = request.args.get("user_id") or request.args.get("id")
    user = None

    if phone:
        user = conn.execute("SELECT * FROM users WHERE phone = ?", (phone.strip(),)).fetchone()

    if not user and user_id is not None:
        resolved_id = resolve_user_id(conn, user_id)
        if resolved_id is not None:
            user = conn.execute("SELECT * FROM users WHERE id = ?", (resolved_id,)).fetchone()

    if not user and not phone and user_id is None:
        user = conn.execute("SELECT * FROM users ORDER BY id ASC LIMIT 1").fetchone()

    conn.close()
    if user:
        return jsonify({
            "id": user["id"],
            "name": user["name"],
            "phone": user["phone"],
            "balance": user["balance"],
            "emergency_balance": user["emergency_balance"]
        })
    return jsonify({"error": "User not found"}), 404


@app.route("/api/users", methods=["GET"])
def api_get_all_users():
    conn = get_db()
    users = conn.execute("SELECT id, name, phone, balance, emergency_balance FROM users ORDER BY id ASC").fetchall()
    conn.close()
    return jsonify([dict(u) for u in users])


@app.route("/api/bank/reset-demo", methods=["POST"])
def api_bank_reset_demo():
    conn = get_db()
    conn.execute("UPDATE users SET balance = 500.0, emergency_balance = 10.0")
    conn.commit()
    user = conn.execute("SELECT * FROM users ORDER BY id ASC LIMIT 1").fetchone()
    conn.close()
    return jsonify({"success": True, "message": "Reset to official demo balances (₹500.00 Online, ₹10.00 Emergency)", "user": dict(user)})


@app.route("/api/users/<user_id>", methods=["GET", "PATCH"])
def api_user_detail(user_id):
    conn = get_db()
    resolved_id = resolve_user_id(conn, user_id)
    user = None
    if resolved_id is not None:
        user = conn.execute("SELECT * FROM users WHERE id = ?", (resolved_id,)).fetchone()
    if not user:
        # Check by phone directly
        user = conn.execute("SELECT * FROM users WHERE phone = ?", (str(user_id).strip(),)).fetchone()
        if user:
            resolved_id = user["id"]

    if not user:
        conn.close()
        return jsonify({"error": "User not found"}), 404

    if request.method == "PATCH":
        data = request.get_json(silent=True) or request.form
        name = data.get("name")
        phone = data.get("phone")
        if name:
            conn.execute("UPDATE users SET name = ? WHERE id = ?", (name, resolved_id))
        if phone:
            conn.execute("UPDATE users SET phone = ? WHERE id = ?", (phone, resolved_id))
        conn.commit()
        user = conn.execute("SELECT * FROM users WHERE id = ?", (resolved_id,)).fetchone()

    conn.close()
    return jsonify({
        "id": user["id"],
        "name": user["name"],
        "phone": user["phone"],
        "balance": user["balance"],
        "emergency_balance": user["emergency_balance"]
    })


@app.route("/api/users/phone/<phone>", methods=["GET"])
def api_user_by_phone(phone):
    conn = get_db()
    user = conn.execute("SELECT * FROM users WHERE phone = ?", (phone.strip(),)).fetchone()
    conn.close()
    if user:
        return jsonify({
            "id": user["id"],
            "name": user["name"],
            "phone": user["phone"],
            "balance": user["balance"],
            "emergency_balance": user["emergency_balance"]
        })
    return jsonify({"error": "User not found"}), 404


@app.route("/api/user/register", methods=["POST"])
def api_register_user():
    data = request.get_json(silent=True) or request.form
    name = (data.get("name") or "New Customer").strip()
    phone = (data.get("phone") or "").strip()
    try:
        initial_balance = float(data.get("balance", 1000.0))
    except (ValueError, TypeError):
        initial_balance = 1000.0
    try:
        emergency_balance = float(data.get("emergency_balance", 100.0))
    except (ValueError, TypeError):
        emergency_balance = 100.0

    if not phone:
        return jsonify({"success": False, "message": "Phone number is required"}), 400

    conn = get_db()
    user = conn.execute("SELECT * FROM users WHERE phone = ?", (phone,)).fetchone()
    if user:
        new_bal = max(float(user["balance"]), initial_balance)
        conn.execute("UPDATE users SET name = ?, balance = ? WHERE id = ?", (name, new_bal, user["id"]))
        conn.commit()
        updated = conn.execute("SELECT * FROM users WHERE id = ?", (user["id"],)).fetchone()
        conn.close()
        return jsonify({"success": True, "message": "User updated", "user": dict(updated)})
    else:
        conn.execute(
            "INSERT INTO users (name, phone, balance, emergency_balance) VALUES (?, ?, ?, ?)",
            (name, phone, initial_balance, emergency_balance)
        )
        conn.commit()
        created = conn.execute("SELECT * FROM users WHERE phone = ?", (phone,)).fetchone()
        conn.close()
        return jsonify({"success": True, "message": f"User registered with ₹{initial_balance:.2f} online bank balance", "user": dict(created)})


@app.route("/api/user/add-funds", methods=["POST"])
def api_add_funds():
    data = request.get_json(silent=True) or request.form
    amount = float(data.get("amount", 0))
    target = data.get("target", "balance")  # "balance" or "emergency_balance"
    raw_user_id = data.get("user_id", 1)

    if amount <= 0:
        return jsonify({"success": False, "message": "Invalid amount"}), 400

    conn = get_db()
    user_id = resolve_user_id(conn, raw_user_id) or 1
    if target == "emergency_balance":
        conn.execute("UPDATE users SET emergency_balance = emergency_balance + ? WHERE id = ?", (amount, user_id))
    else:
        conn.execute("UPDATE users SET balance = balance + ? WHERE id = ?", (amount, user_id))
    conn.commit()

    user = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    conn.close()
    return jsonify({
        "success": True,
        "message": f"Successfully added ₹{amount:.2f}",
        "balance": user["balance"] if user else 0.0,
        "emergency_balance": user["emergency_balance"] if user else 0.0
    })


@app.route("/api/user/transfer-to-emergency", methods=["POST"])
def api_transfer_to_emergency():
    data = request.get_json(silent=True) or request.form
    try:
        amount = float(data.get("amount", 0))
    except (ValueError, TypeError):
        return jsonify({"success": False, "message": "Invalid amount"}), 400

    raw_user_id = data.get("user_id", 1)

    if amount <= 0:
        return jsonify({"success": False, "message": "Amount must be greater than zero"}), 400

    conn = get_db()
    user_id = resolve_user_id(conn, raw_user_id) or 1
    user = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    if not user:
        conn.close()
        return jsonify({"success": False, "message": "User not found"}), 404

    if user["balance"] < amount:
        conn.close()
        return jsonify({
            "success": False,
            "message": f"Insufficient available balance (₹{user['balance']:.2f}) to transfer ₹{amount:.2f}"
        }), 400

    new_bal = round(user["balance"] - amount, 2)
    new_emg = round(user["emergency_balance"] + amount, 2)

    conn.execute("UPDATE users SET balance = ?, emergency_balance = ? WHERE id = ?", (new_bal, new_emg, user_id))
    conn.commit()

    updated = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    conn.close()

    return jsonify({
        "success": True,
        "message": f"Successfully transferred ₹{amount:.2f} to Emergency Balance",
        "balance": updated["balance"],
        "emergency_balance": updated["emergency_balance"]
    })


@app.route("/api/merchants", methods=["GET"])
def api_get_merchants():
    conn = get_db()
    merchants = conn.execute("SELECT * FROM merchants ORDER BY is_essential DESC, distance ASC").fetchall()
    conn.close()
    return jsonify([dict(m) for m in merchants])


@app.route("/api/system/network-status", methods=["GET", "POST"])
def api_network_status():
    if request.method == "POST":
        data = request.get_json(silent=True) or request.form
        status = data.get("status", "online")
        set_network_status(status)
        return jsonify({"status": status})
    return jsonify({"status": get_network_status()})


@app.route("/api/system/toggle-network", methods=["POST"])
def api_toggle_network():
    data = request.get_json(silent=True) or request.form
    status = data.get("status", "online")
    set_network_status(status)
    return jsonify({"status": status, "message": f"Network mode changed to {status}"})


@app.route("/api/bluetooth/scan", methods=["POST"])
def api_bluetooth_scan():
    conn = get_db()
    merchants = conn.execute("SELECT * FROM merchants").fetchall()
    users = conn.execute("SELECT id, name, phone FROM users WHERE id != 1").fetchall()
    conn.close()

    user_list = [dict(u) for u in users]
    # Guarantee Mr. Sukhpal is present in nearby devices list
    if not any("sukhpal" in (u.get("name") or "").lower() for u in user_list):
        user_list.insert(0, {
            "id": 5,
            "name": "Sukhpal Singh",
            "phone": "9811122233"
        })

    discovered_merchants = [
        {
            "id": m["id"],
            "name": m["name"],
            "category": m["category"],
            "distance": round(random.uniform(0.8, 5.5), 1),
            "isInRange": True,
            "type": "merchant"
        }
        for m in merchants[:5]
    ]

    discovered_users = [
        {
            "id": u["id"],
            "name": u["name"],
            "phone": u["phone"],
            "distance": round(random.uniform(1.2, 6.0), 1),
            "isInRange": True,
            "type": "user"
        }
        for u in user_list
    ]

    return jsonify({
        "merchants": discovered_merchants,
        "users": discovered_users
    })


@app.route("/api/transactions", methods=["GET", "POST"])
def api_transactions():
    conn = get_db()
    if request.method == "POST":
        data = request.get_json(silent=True) or request.form
        payee = data.get("payee") or "Unknown"
        amount = float(data.get("amount", 0))
        note = data.get("note", "")
        raw_offline = data.get("is_offline")
        is_offline = 1 if raw_offline in [True, "true", 1, "1"] else 0

        user = conn.execute("SELECT * FROM users WHERE id = 1").fetchone()
        current_balance = user["balance"] if user else 500.0
        current_emergency = user["emergency_balance"] if user else 100.0

        if not is_offline:
            status = "completed"
            channel = "UPI"
            method = "UPI"
            tx_id = data.get("tx_id") or f"UPI{random.randint(100000, 999999)}"
            new_balance = max(0.0, current_balance - amount)
            conn.execute("UPDATE users SET balance = ? WHERE id = 1", (new_balance,))
        else:
            status = "pending"
            channel = "Bluetooth"
            method = "BLUETOOTH"
            tx_id = data.get("tx_id") or f"BT{random.randint(100000, 999999)}"
            new_emergency = max(0.0, current_emergency - amount)
            conn.execute("UPDATE users SET emergency_balance = ? WHERE id = 1", (new_emergency,))

        conn.execute("""
            INSERT INTO transactions (tx_id, sender_id, payee, amount, status, channel, note, is_offline, method)
            VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?)
        """, (tx_id, payee, amount, status, channel, note, is_offline, method))
        conn.commit()

        tx = conn.execute("SELECT * FROM transactions WHERE tx_id = ?", (tx_id,)).fetchone()
        conn.close()
        return jsonify({"status": "success", "data": dict(tx)})

    txs = conn.execute("SELECT * FROM transactions ORDER BY created_at DESC").fetchall()
    conn.close()
    return jsonify([dict(t) for t in txs])


@app.route("/api/transactions/clear", methods=["POST", "DELETE"])
def api_clear_transactions():
    conn = get_db()
    conn.execute("DELETE FROM transactions")
    conn.commit()
    conn.close()
    return jsonify({
        "status": "success",
        "message": "Transaction history cleared successfully",
        "count": 0
    })


@app.route("/insert", methods=["GET", "POST"])
def insert():
    if request.method == "POST":
        data = request.get_json(silent=True) or request.form
        payee = data.get("payee", "Unknown")
        amount = float(data.get("amount", 0))
        note = data.get("note", "")
        raw_offline = data.get("is_offline")
        is_offline = 1 if raw_offline in [True, "true", 1, "1"] else 0

        conn = get_db()
        user = conn.execute("SELECT * FROM users WHERE id = 1").fetchone()
        current_balance = user["balance"] if user else 500.0
        current_emergency = user["emergency_balance"] if user else 100.0

        if not is_offline:
            # Online: Normal UPI payment, reduce from Available Balance
            channel = "UPI"
            method = "UPI"
            status = "completed"
            tx_id = data.get("tx_id") or f"UPI{random.randint(100000, 999999)}"
            new_balance = max(0.0, current_balance - amount)
            new_emergency = current_emergency
            conn.execute("UPDATE users SET balance = ? WHERE id = 1", (new_balance,))
        else:
            # Offline/Emergency: Bluetooth payment, reduce from Emergency Balance
            channel = "Bluetooth"
            method = "BLUETOOTH"
            status = "pending"
            tx_id = data.get("tx_id") or f"BT{random.randint(100000, 999999)}"
            new_balance = current_balance
            new_emergency = max(0.0, current_emergency - amount)
            conn.execute("UPDATE users SET emergency_balance = ? WHERE id = 1", (new_emergency,))

        conn.execute("""
            INSERT INTO transactions (tx_id, sender_id, payee, amount, status, channel, note, is_offline, method)
            VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?)
        """, (tx_id, payee, amount, status, channel, note, is_offline, method))
        conn.commit()

        updated_user = conn.execute("SELECT * FROM users WHERE id = 1").fetchone()
        conn.close()

        return jsonify({
            "status": "success",
            "message": "Payment recorded successfully",
            "data": {
                "tx_id": tx_id,
                "payee": payee,
                "amount": amount,
                "note": note,
                "channel": channel,
                "status": status,
                "is_offline": bool(is_offline),
                "method": method,
                "balance": updated_user["balance"] if updated_user else new_balance,
                "emergency_balance": updated_user["emergency_balance"] if updated_user else new_emergency
            }
        })
    return render_template("emergencypay.html")


@app.route("/api/transactions/sync", methods=["POST"])
def api_transactions_sync():
    """Reconciliation endpoint: Reconciles all pending offline mesh transactions when back online"""
    data = request.get_json(silent=True) or {}
    local_txs = data.get("transactions", [])
    conn = get_db()
    raw_uid = data.get("user_id", 1)
    user_id = resolve_user_id(conn, raw_uid) or 1

    reconciled_count = 0
    total_spent = 0.0
    total_received = 0.0

    # Reconcile any client-side offline transactions not yet in DB
    for t in local_txs:
        t_id = t.get("tx_id") or t.get("id") or t.get("txId")
        if t_id:
            exists = conn.execute("SELECT id FROM transactions WHERE tx_id = ?", (str(t_id),)).fetchone()
            if not exists:
                payee = t.get("payee", "Unknown")
                amount = float(t.get("amount", 0))
                note = t.get("note", "")
                direction = str(t.get("direction", "SENT")).upper()
                if "RECEIVED" in note.upper():
                    direction = "RECEIVED"

                sender_id = resolve_user_id(conn, t.get("sender_id", user_id) or user_id) or user_id
                raw_receiver = t.get("receiver_id")

                # Resolve receiver user ID if payee/receiver is a registered bank user
                receiver_user_id = None
                if raw_receiver:
                    cand_id = resolve_user_id(conn, raw_receiver)
                    if cand_id:
                        r_chk = conn.execute("SELECT id FROM users WHERE id = ?", (cand_id,)).fetchone()
                        if r_chk:
                            receiver_user_id = cand_id
                if not receiver_user_id and payee:
                    u_match = conn.execute(
                        "SELECT id FROM users WHERE phone = ? OR LOWER(TRIM(name)) = LOWER(TRIM(?))",
                        (payee.strip(), payee.strip())
                    ).fetchone()
                    if u_match:
                        receiver_user_id = u_match["id"]

                # Self-Transfer Protection: Based on unique identifiers (User ID, unique phone number) - NOT name
                curr_user = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
                user_phone = str(curr_user["phone"]).strip() if curr_user else ""
                expected_acc = f"ACC-{user_phone}" if user_phone else ""
                is_self_transfer = (
                    (receiver_user_id is not None and sender_id == receiver_user_id) or
                    (bool(user_phone) and payee.strip() == user_phone) or
                    (bool(expected_acc) and expected_acc in payee.strip()) or
                    direction == "SELF"
                )

                conn.execute("""
                    INSERT INTO transactions (tx_id, sender_id, receiver_id, payee, amount, status, channel, note, is_offline, method)
                    VALUES (?, ?, ?, ?, ?, 'completed', 'Bluetooth', ?, 1, 'BLUETOOTH')
                """, (str(t_id), sender_id, receiver_user_id or raw_receiver, payee, amount, note))

                reconciled_count += 1
                if not is_self_transfer and amount > 0:
                    # Bilateral Balance Adjustment:
                    # Debit sender's emergency balance
                    s_user = conn.execute("SELECT * FROM users WHERE id = ?", (sender_id,)).fetchone()
                    if s_user:
                        s_emg = float(s_user["emergency_balance"])
                        s_bal = float(s_user["balance"])
                        if s_emg >= amount:
                            s_emg = round(s_emg - amount, 2)
                        else:
                            rem = amount - s_emg
                            s_emg = 0.0
                            s_bal = max(0.0, round(s_bal - rem, 2))
                        conn.execute("UPDATE users SET balance = ?, emergency_balance = ? WHERE id = ?", (s_bal, s_emg, sender_id))

                    # Credit receiver's emergency balance if receiver is a registered user
                    if receiver_user_id:
                        r_user = conn.execute("SELECT * FROM users WHERE id = ?", (receiver_user_id,)).fetchone()
                        if r_user:
                            r_emg = round(float(r_user["emergency_balance"]) + amount, 2)
                            conn.execute("UPDATE users SET emergency_balance = ? WHERE id = ?", (r_emg, receiver_user_id))

    # Also resolve any legacy pending transactions pre-existing in the DB
    pending_rows = conn.execute("SELECT * FROM transactions WHERE status = 'pending'").fetchall()
    if pending_rows:
        reconciled_count += len(pending_rows)
        conn.execute("UPDATE transactions SET status = 'completed' WHERE status = 'pending'")

    conn.commit()

    all_txs = conn.execute("SELECT * FROM transactions ORDER BY created_at DESC").fetchall()
    updated_user = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    conn.close()

    return jsonify({
        "status": "success",
        "message": f"Successfully synchronized {reconciled_count} transaction(s).",
        "reconciled_count": reconciled_count,
        "balance": updated_user["balance"] if updated_user else 500.0,
        "emergency_balance": updated_user["emergency_balance"] if updated_user else 10.0,
        "transactions": [dict(t) for t in all_txs]
    })


if __name__ == "__main__":
    try:
        from waitress import serve
        print("Waitress serving on http://0.0.0.0:5000")
        serve(app, host="0.0.0.0", port=5000)
    except ImportError:
        print("Starting Flask built-in server on http://0.0.0.0:5000")
        app.run(host="0.0.0.0", port=5000, debug=False)
