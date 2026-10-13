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

    # Seed default user if empty
    cursor.execute("SELECT COUNT(*) as cnt FROM users")
    if cursor.fetchone()["cnt"] == 0:
        cursor.execute("""
            INSERT INTO users (name, phone, balance, emergency_balance)
            VALUES (?, ?, ?, ?)
        """, ("Mr. Sukhpal", "9876543210", 2500.0, 500.0))
        cursor.execute("""
            INSERT INTO users (name, phone, balance, emergency_balance)
            VALUES (?, ?, ?, ?)
        """, ("Pavitra Prabhakar", "8765432109", 1800.0, 400.0))
        cursor.execute("""
            INSERT INTO users (name, phone, balance, emergency_balance)
            VALUES (?, ?, ?, ?)
        """, ("Dr. Doom", "7654321098", 5000.0, 1000.0))
        cursor.execute("""
            INSERT INTO users (name, phone, balance, emergency_balance)
            VALUES (?, ?, ?, ?)
        """, ("Sunsan Storm", "6543210987", 3200.0, 600.0))

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
            "distance": round(random.uniform(1.2, 8.0), 1),
            "isInRange": True,
            "type": "user"
        }
        for u in users
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
        channel = data.get("channel", "Bluetooth")
        status = data.get("status", "completed")
        is_offline = 1 if data.get("is_offline") in [True, "true", 1, "1"] else 0
        tx_id = data.get("tx_id") or f"EMP{random.randint(100000, 999999)}"
        token = data.get("token") or f"EP-MESH-{os.urandom(4).hex().upper()}"
        method = "BLUETOOTH" if "bluetooth" in channel.lower() else ("OFFLINE" if is_offline else "UPI")

        # Deduct balance from user 1
        user = conn.execute("SELECT * FROM users WHERE id = 1").fetchone()
        if user:
            if is_offline and status == "completed":
                conn.execute("UPDATE users SET emergency_balance = MAX(0, emergency_balance - ?) WHERE id = 1", (amount,))
            elif not is_offline and status == "completed":
                conn.execute("UPDATE users SET balance = MAX(0, balance - ?) WHERE id = 1", (amount,))

        conn.execute("""
            INSERT INTO transactions (tx_id, sender_id, payee, amount, status, channel, note, token, is_offline, method)
            VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (tx_id, payee, amount, status, channel, note, token, is_offline, method))
        conn.commit()

        tx = conn.execute("SELECT * FROM transactions WHERE tx_id = ?", (tx_id,)).fetchone()
        conn.close()
        return jsonify({"status": "success", "data": dict(tx)})

    txs = conn.execute("SELECT * FROM transactions ORDER BY created_at DESC").fetchall()
    conn.close()
    return jsonify([dict(t) for t in txs])


@app.route("/insert", methods=["GET", "POST"])
def insert():
    if request.method == "POST":
        data = request.get_json(silent=True) or request.form
        payee = data.get("payee", "Unknown")
        amount = float(data.get("amount", 0))
        note = data.get("note", "")
        channel = data.get("channel", "Bluetooth")
        status = data.get("status", "pending")
        is_offline = 1
        tx_id = data.get("tx_id") or f"EMP{random.randint(100000, 999999)}"
        token = data.get("token") or f"EP-MESH-{os.urandom(4).hex().upper()}"
        method = "BLUETOOTH" if "bluetooth" in channel.lower() else "OFFLINE"

        conn = get_db()
        conn.execute("""
            INSERT INTO transactions (tx_id, sender_id, payee, amount, status, channel, note, token, is_offline, method)
            VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (tx_id, payee, amount, status, channel, note, token, is_offline, method))
        conn.commit()
        conn.close()

        return jsonify({
            "status": "success",
            "message": "Payment recorded offline successfully",
            "data": {
                "tx_id": tx_id,
                "payee": payee,
                "amount": amount,
                "note": note,
                "channel": channel,
                "token": token,
                "status": status,
                "is_offline": True,
                "method": method
            }
        })
    return render_template("emergencypay.html")


@app.route("/api/transactions/sync", methods=["POST"])
def api_transactions_sync():
    """Reconciliation endpoint: Reconciles all pending offline mesh transactions when back online"""
    conn = get_db()
    # Mark pending transactions as completed
    pending_count = conn.execute("SELECT COUNT(*) as cnt FROM transactions WHERE status = 'pending'").fetchone()["cnt"]
    conn.execute("UPDATE transactions SET status = 'completed' WHERE status = 'pending'")
    conn.commit()

    all_txs = conn.execute("SELECT * FROM transactions ORDER BY created_at DESC").fetchall()
    conn.close()

    return jsonify({
        "status": "success",
        "message": f"Successfully reconciled {pending_count} pending offline transaction(s).",
        "reconciled_count": pending_count,
        "transactions": [dict(t) for t in all_txs]
    })


if __name__ == "__main__":
    from waitress import serve
    print("Waitress serving on http://0.0.0.0:5000")
    serve(app, host="0.0.0.0", port=5000)
