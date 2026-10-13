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

    # Ensure primary user Rahul exists at id 1
    cursor.execute("SELECT * FROM users WHERE id = 1")
    user1 = cursor.fetchone()
    if user1:
        if user1["name"] != "Rahul" or user1["phone"] != "9876543210":
            cursor.execute("""
                UPDATE users SET name = 'Rahul', phone = '9876543210', balance = 69420.0, emergency_balance = 6767.0
                WHERE id = 1
            """)
    else:
        cursor.execute("""
            INSERT INTO users (id, name, phone, balance, emergency_balance)
            VALUES (1, 'Rahul', '9876543210', 69420.0, 6767.0)
        """)

    # Ensure Mr. Sukhpal exists as a separate user (id != 1)
    cursor.execute("SELECT * FROM users WHERE name = 'Mr. Sukhpal' AND id != 1")
    if not cursor.fetchone():
        cursor.execute("SELECT * FROM users WHERE phone = '9811122233'")
        if not cursor.fetchone():
            cursor.execute("""
                INSERT INTO users (name, phone, balance, emergency_balance)
                VALUES ('Mr. Sukhpal', '9811122233', 12500.0, 2500.0)
            """)

    # Ensure demo peers exist
    demo_users = [
        ("Pavitra Prabhakar", "8765432109", 1800.0, 400.0),
        ("Dr. Doom", "7654321098", 5000.0, 1000.0),
        ("Sunsan Storm", "6543210987", 3200.0, 600.0)
    ]
    for d_name, d_phone, d_bal, d_emg in demo_users:
        cursor.execute("SELECT * FROM users WHERE phone = ?", (d_phone,))
        if not cursor.fetchone():
            cursor.execute("""
                INSERT INTO users (name, phone, balance, emergency_balance)
                VALUES (?, ?, ?, ?)
            """, (d_name, d_phone, d_bal, d_emg))

    # Seed default merchants if empty
    cursor.execute("SELECT COUNT(*) as cnt FROM merchants")
    if cursor.fetchone()["cnt"] == 0:
        default_merchants = [
            ("S.H.I.E.L.D Daycare", "9876543211", "Healthcare", 1, 1.2, "Sector 17, Main Market"),
            ("Spidey Pizza Service", "9876543212", "Groceries", 1, 0.8, "Queens Main Street"),
            ("Widow Spa", "9876543213", "Healthcare", 1, 1.5, "Red Room Avenue"),
            ("Panther Jewellers", "9876543214", "Retail", 0, 2.4, "Wakanda Plaza"),
            ("Electrician(God of Hammers) Services", "9876543215", "Emergency Services", 1, 1.8, "Asgardian Junction")
        ]
        cursor.executemany("""
            INSERT INTO merchants (name, phone, category, is_essential, distance, address)
            VALUES (?, ?, ?, ?, ?, ?)
        """, default_merchants)

    # Ensure Spidey Pizza Service is present
    cursor.execute("SELECT id FROM merchants WHERE name = 'Spidey Pizza Service'")
    if not cursor.fetchone():
        cursor.execute("""
            INSERT INTO merchants (name, phone, category, is_essential, distance, address)
            VALUES ('Spidey Pizza Service', '9876543212', 'Groceries', 1, 0.8, 'Queens Main Street')
        """)

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
# REST API ENDPOINTS
# ==========================================

@app.route("/api/user", methods=["GET"])
def api_get_user():
    conn = get_db()
    user = conn.execute("SELECT * FROM users WHERE id = 1").fetchone()
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


@app.route("/api/users/<int:user_id>", methods=["GET", "PATCH"])
def api_user_detail(user_id):
    conn = get_db()
    if request.method == "PATCH":
        data = request.get_json(silent=True) or request.form
        name = data.get("name")
        phone = data.get("phone")
        if name:
            conn.execute("UPDATE users SET name = ? WHERE id = ?", (name, user_id))
        if phone:
            conn.execute("UPDATE users SET phone = ? WHERE id = ?", (phone, user_id))
        conn.commit()

    user = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
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


@app.route("/api/users/phone/<phone>", methods=["GET"])
def api_user_by_phone(phone):
    conn = get_db()
    user = conn.execute("SELECT * FROM users WHERE phone = ?", (phone,)).fetchone()
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


@app.route("/api/user/add-funds", methods=["POST"])
def api_add_funds():
    data = request.get_json(silent=True) or request.form
    amount = float(data.get("amount", 0))
    target = data.get("target", "balance")  # "balance" or "emergency_balance"
    user_id = int(data.get("user_id", 1))

    if amount <= 0:
        return jsonify({"success": False, "message": "Invalid amount"}), 400

    conn = get_db()
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
        "balance": user["balance"],
        "emergency_balance": user["emergency_balance"]
    })


@app.route("/api/user/transfer-to-emergency", methods=["POST"])
def api_transfer_to_emergency():
    data = request.get_json(silent=True) or request.form
    try:
        amount = float(data.get("amount", 0))
    except (ValueError, TypeError):
        return jsonify({"success": False, "message": "Invalid amount"}), 400

    user_id = int(data.get("user_id", 1))

    if amount <= 0:
        return jsonify({"success": False, "message": "Amount must be greater than zero"}), 400

    conn = get_db()
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
            "name": "Mr. Sukhpal",
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
        current_balance = user["balance"] if user else 69420.0
        current_emergency = user["emergency_balance"] if user else 6767.0

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
        current_balance = user["balance"] if user else 69420.0
        current_emergency = user["emergency_balance"] if user else 6767.0

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

    # Reconcile any client-side offline transactions not yet in DB
    for t in local_txs:
        t_id = t.get("tx_id") or t.get("id") or t.get("txId")
        if t_id:
            exists = conn.execute("SELECT id FROM transactions WHERE tx_id = ?", (str(t_id),)).fetchone()
            if not exists:
                payee = t.get("payee", "Unknown")
                amount = float(t.get("amount", 0))
                note = t.get("note", "")
                conn.execute("""
                    INSERT INTO transactions (tx_id, sender_id, payee, amount, status, channel, note, is_offline, method)
                    VALUES (?, 1, ?, ?, 'completed', 'Bluetooth', ?, 1, 'BLUETOOTH')
                """, (str(t_id), payee, amount, note))

    # Mark all pending transactions as completed, and adjust online balance
    pending = conn.execute("SELECT * FROM transactions WHERE status = 'pending'").fetchall()
    pending_count = len(pending)
    total_spent_offline = sum(float(p["amount"]) for p in pending)
    if total_spent_offline > 0:
        conn.execute("UPDATE users SET balance = MAX(0, balance - ?) WHERE id = 1", (total_spent_offline,))

    conn.execute("UPDATE transactions SET status = 'completed' WHERE status = 'pending'")
    conn.commit()

    all_txs = conn.execute("SELECT * FROM transactions ORDER BY created_at DESC").fetchall()
    user = conn.execute("SELECT * FROM users WHERE id = 1").fetchone()
    conn.close()

    return jsonify({
        "status": "success",
        "message": f"Successfully synchronized {pending_count} pending transaction(s).",
        "reconciled_count": pending_count,
        "balance": user["balance"] if user else 69420.0,
        "emergency_balance": user["emergency_balance"] if user else 6767.0,
        "transactions": [dict(t) for t in all_txs]
    })


if __name__ == "__main__":
    from waitress import serve
    print("Waitress serving on http://0.0.0.0:5000")
    serve(app, host="0.0.0.0", port=5000)
