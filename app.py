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
            username TEXT,
            name TEXT NOT NULL,
            phone TEXT UNIQUE NOT NULL,
            password TEXT DEFAULT '123456',
            balance REAL DEFAULT 2500.0,
            emergency_balance REAL DEFAULT 500.0,
            keypair TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)
    try:
        cursor.execute("ALTER TABLE users ADD COLUMN username TEXT")
    except Exception:
        pass
    try:
        cursor.execute("ALTER TABLE users ADD COLUMN password TEXT DEFAULT '123456'")
    except Exception:
        pass
    try:
        cursor.execute("ALTER TABLE users ADD COLUMN previous_names TEXT DEFAULT ''")
    except Exception:
        pass
    try:
        cursor.execute("ALTER TABLE users ADD COLUMN is_logged_in INTEGER DEFAULT 0")
    except Exception:
        pass
    try:
        cursor.execute("ALTER TABLE users ADD COLUMN active_device_id TEXT DEFAULT ''")
    except Exception:
        pass
    try:
        cursor.execute("ALTER TABLE users ADD COLUMN session_token TEXT DEFAULT ''")
    except Exception:
        pass
    try:
        cursor.execute("ALTER TABLE users ADD COLUMN last_active TIMESTAMP")
    except Exception:
        pass

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

    # Ensure evaluation account Rahul Sharma exists at id 1 if table is empty
    cursor.execute("SELECT * FROM users WHERE id = 1")
    user1 = cursor.fetchone()
    if not user1:
        cursor.execute("""
            INSERT INTO users (id, name, username, phone, password, balance, emergency_balance, previous_names)
            VALUES (1, 'Rahul Sharma', 'rahul', '9876543210', 'password', 1250.0, 200.0, '')
        """)

    # Clean up legacy mock/superhero users
    cursor.execute("DELETE FROM users WHERE name IN ('Dr. Doom', 'Sunsan Storm', 'Pavitra Prabhakar', 'Mr. Sukhpal', 'rabel')")

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
    Resolves an ID, phone number, username, or name to a valid database user ID.
    """
    if user_id is None:
        return 1
    
    val_str = str(user_id).strip()
    if not val_str:
        return 1

    # 1. Check exact integer ID
    try:
        uid = int(val_str)
        row = conn.execute("SELECT id FROM users WHERE id = ?", (uid,)).fetchone()
        if row:
            return row["id"]
        # Fallback to demo phone map if old index
        if uid in DEMO_PHONE_MAP:
            p_row = conn.execute("SELECT id FROM users WHERE phone = ?", (DEMO_PHONE_MAP[uid],)).fetchone()
            if p_row:
                return p_row["id"]
    except (ValueError, TypeError):
        pass

    # 2. Check by phone number
    clean_digits = "".join(filter(str.isdigit, val_str))[-10:] if any(c.isdigit() for c in val_str) else val_str
    p_row = conn.execute("SELECT id FROM users WHERE phone = ? OR phone LIKE ?", (val_str, f"%{clean_digits}%")).fetchone()
    if p_row:
        return p_row["id"]

    # 3. Check by username or name
    u_row = conn.execute("SELECT id FROM users WHERE username = ? COLLATE NOCASE OR name = ? COLLATE NOCASE", (val_str, val_str)).fetchone()
    if u_row:
        return u_row["id"]

    return None


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
            "emergency_balance": user["emergency_balance"],
            "previous_names": user["previous_names"] if "previous_names" in user.keys() else ""
        })
    return jsonify({"error": "User not found"}), 404


@app.route("/api/users", methods=["GET"])
def api_get_all_users():
    conn = get_db()
    users = conn.execute("SELECT id, name, phone, balance, emergency_balance, previous_names, is_logged_in, active_device_id FROM users ORDER BY name ASC").fetchall()
    conn.close()
    return jsonify([dict(u) for u in users])


@app.route("/api/users/search", methods=["GET"])
def api_users_search():
    q = request.args.get("q", "").strip()
    conn = get_db()
    if q:
        rows = conn.execute(
            "SELECT id, name, phone, balance, emergency_balance, previous_names, is_logged_in, active_device_id FROM users WHERE name LIKE ? OR phone LIKE ? OR username LIKE ? ORDER BY name ASC",
            (f"%{q}%", f"%{q}%", f"%{q}%")
        ).fetchall()
    else:
        rows = conn.execute("SELECT id, name, phone, balance, emergency_balance, previous_names, is_logged_in, active_device_id FROM users ORDER BY name ASC").fetchall()
    conn.close()
    return jsonify([dict(r) for r in rows])


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
        balance = data.get("balance")
        emergency_balance = data.get("emergency_balance")
        if name:
            conn.execute("UPDATE users SET name = ? WHERE id = ?", (name, resolved_id))
        if phone:
            conn.execute("UPDATE users SET phone = ? WHERE id = ?", (phone, resolved_id))
        if balance is not None:
            conn.execute("UPDATE users SET balance = ? WHERE id = ?", (float(balance), resolved_id))
        if emergency_balance is not None:
            conn.execute("UPDATE users SET emergency_balance = ? WHERE id = ?", (float(emergency_balance), resolved_id))
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
    username = (data.get("username") or name).strip()
    phone = (data.get("phone") or "").strip()
    password = (data.get("password") or "123456").strip()
    device_id = (data.get("device_id") or "").strip()
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
        is_logged_in = user["is_logged_in"] if "is_logged_in" in user.keys() else 0
        active_dev = str(user["active_device_id"] or "").strip() if "active_device_id" in user.keys() else ""
        if is_logged_in == 1 and active_dev and device_id and active_dev != device_id:
            conn.close()
            return jsonify({
                "success": False,
                "error_code": "SIMULTANEOUS_LOGIN_RESTRICTED",
                "message": f"Security Alert: Account '{user['name']}' is already actively signed in on another device. Simultaneous logins are strictly prohibited. You must sign out from the other device first."
            }), 403

        new_bal = max(float(user["balance"]), initial_balance)
        conn.execute(
            "UPDATE users SET name = ?, username = ?, password = ?, balance = ?, is_logged_in = 1, active_device_id = ?, last_active = CURRENT_TIMESTAMP WHERE id = ?",
            (name, username, password, new_bal, device_id, user["id"])
        )
        conn.commit()
        updated = conn.execute("SELECT * FROM users WHERE id = ?", (user["id"],)).fetchone()
        conn.close()
        return jsonify({"success": True, "message": "User updated and signed in", "user": dict(updated)})
    else:
        conn.execute(
            "INSERT INTO users (name, username, phone, password, balance, emergency_balance, is_logged_in, active_device_id, last_active) VALUES (?, ?, ?, ?, ?, ?, 1, ?, CURRENT_TIMESTAMP)",
            (name, username, phone, password, initial_balance, emergency_balance, device_id)
        )
        conn.commit()
        created = conn.execute("SELECT * FROM users WHERE phone = ?", (phone,)).fetchone()
        conn.close()
        return jsonify({"success": True, "message": f"User registered with ₹{initial_balance:.2f} online bank balance", "user": dict(created)})


@app.route("/api/user/login", methods=["POST"])
def api_login_user():
    data = request.get_json(silent=True) or request.form
    identifier = (data.get("username") or data.get("phone") or "").strip()
    password = (data.get("password") or "").strip()
    device_id = (data.get("device_id") or "").strip()

    if not identifier or not password:
        return jsonify({"success": False, "message": "Username/phone and password are required"}), 400

    conn = get_db()
    clean_digits = "".join(filter(str.isdigit, identifier))[-10:] if any(c.isdigit() for c in identifier) else identifier

    user = conn.execute("""
        SELECT * FROM users 
        WHERE (username = ? COLLATE NOCASE OR name = ? COLLATE NOCASE OR phone = ? OR phone LIKE ?)
        ORDER BY id ASC LIMIT 1
    """, (identifier, identifier, identifier, f"%{clean_digits}%")).fetchone()

    if user:
        # Check password or allow master/default passwords for smooth testing
        user_pwd = user["password"] if "password" in user.keys() else "123456"
        if user_pwd == password or password in ("123456", "password", "rahul@12345") or user_pwd in ("123456", "password"):
            if user_pwd != password and password:
                conn.execute("UPDATE users SET password = ? WHERE id = ?", (password, user["id"]))
                conn.commit()

            # SINGLE ACTIVE DEVICE ENFORCEMENT:
            is_logged_in = user["is_logged_in"] if "is_logged_in" in user.keys() else 0
            active_dev = str(user["active_device_id"] or "").strip() if "active_device_id" in user.keys() else ""

            if is_logged_in == 1 and active_dev and device_id and active_dev != device_id:
                conn.close()
                return jsonify({
                    "success": False,
                    "error_code": "SIMULTANEOUS_LOGIN_RESTRICTED",
                    "message": f"Security Alert: Account '{user['name']}' is already actively signed in on another device. Simultaneous logins are strictly prohibited to prevent duplicate spending. You must sign out from the other device first."
                }), 403

            new_session_token = str(uuid.uuid4())
            conn.execute("""
                UPDATE users 
                SET is_logged_in = 1, active_device_id = ?, session_token = ?, last_active = CURRENT_TIMESTAMP 
                WHERE id = ?
            """, (device_id, new_session_token, user["id"]))
            conn.commit()

            user_dict = dict(user)
            user_dict["is_logged_in"] = 1
            user_dict["active_device_id"] = device_id
            user_dict["session_token"] = new_session_token
            conn.close()
            return jsonify({"success": True, "message": "Login successful", "user": user_dict})
        else:
            conn.close()
            return jsonify({"success": False, "message": "Invalid password"}), 401

    conn.close()
    return jsonify({"success": False, "message": "User not found"}), 404


@app.route("/api/user/logout", methods=["POST"])
def api_logout_user():
    data = request.get_json(silent=True) or request.form
    raw_uid = data.get("user_id")
    phone = (data.get("phone") or "").strip()
    device_id = (data.get("device_id") or "").strip()

    conn = get_db()
    user_id = resolve_user_id(conn, raw_uid) if raw_uid else None
    if not user_id and phone:
        u = conn.execute("SELECT id FROM users WHERE phone = ?", (phone,)).fetchone()
        if u:
            user_id = u["id"]

    if user_id:
        conn.execute("UPDATE users SET is_logged_in = 0, active_device_id = '', session_token = '' WHERE id = ?", (user_id,))
        conn.commit()
    elif device_id:
        conn.execute("UPDATE users SET is_logged_in = 0, active_device_id = '', session_token = '' WHERE active_device_id = ?", (device_id,))
        conn.commit()

    conn.close()
    return jsonify({"success": True, "message": "User signed out. Device session released."})


@app.route("/api/user/force-logout/<user_id>", methods=["POST"])
def api_force_logout(user_id):
    conn = get_db()
    uid = resolve_user_id(conn, user_id)
    if uid:
        conn.execute("UPDATE users SET is_logged_in = 0, active_device_id = '', session_token = '' WHERE id = ?", (uid,))
        conn.commit()
        conn.close()
        return jsonify({"success": True, "message": f"Account #{uid} session force-unlocked."})
    conn.close()
    return jsonify({"success": False, "message": "User not found"}), 404


@app.route("/api/user/add-funds", methods=["POST"])
def api_add_funds():
    data = request.get_json(silent=True) or request.form
    try:
        amount = float(data.get("amount", 0))
    except (ValueError, TypeError):
        amount = 0.0
    target = data.get("target", "balance")  # "balance" or "emergency_balance"
    raw_user_id = data.get("user_id")
    phone = (data.get("phone") or "").strip()
    username = (data.get("username") or "").strip()

    if amount <= 0:
        return jsonify({"success": False, "message": "Invalid amount"}), 400

    conn = get_db()
    user_id = resolve_user_id(conn, raw_user_id)
    if not user_id and phone:
        user_id = resolve_user_id(conn, phone)
    if not user_id and username:
        user_id = resolve_user_id(conn, username)

    if not user_id:
        # Auto-create if new user profile adds funds
        ident_phone = phone if phone else "9876543210"
        ident_name = username if username else (f"User_{raw_user_id}" if raw_user_id else "User")
        conn.execute(
            "INSERT INTO users (name, username, phone, balance, emergency_balance, previous_names) VALUES (?, ?, ?, ?, ?, '')",
            (ident_name, ident_name, ident_phone, amount if target != "emergency_balance" else 1000.0, amount if target == "emergency_balance" else 100.0)
        )
        conn.commit()
        created = conn.execute("SELECT * FROM users WHERE phone = ?", (ident_phone,)).fetchone()
        user_id = created["id"]
    else:
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
        "balance": user["balance"] if user else 500.0,
        "emergency_balance": user["emergency_balance"] if user else 100.0
    })


@app.route("/api/user/update-name", methods=["POST"])
def api_update_user_name():
    data = request.get_json(silent=True) or request.form
    user_id = data.get("user_id")
    phone = (data.get("phone") or "").strip()
    new_name = (data.get("name") or "").strip()

    if not new_name:
        return jsonify({"success": False, "message": "Name cannot be empty"}), 400

    conn = get_db()
    resolved_id = resolve_user_id(conn, user_id)
    if not resolved_id and phone:
        resolved_id = resolve_user_id(conn, phone)

    if not resolved_id:
        conn.close()
        return jsonify({"success": False, "message": "User not found"}), 404

    user = conn.execute("SELECT * FROM users WHERE id = ?", (resolved_id,)).fetchone()
    old_name = user["name"] if user else ""
    prev_str = user["previous_names"] if user and "previous_names" in user.keys() and user["previous_names"] else ""
    prev_list = [p.strip() for p in prev_str.split(",") if p.strip()]
    if old_name and old_name != new_name and old_name not in prev_list:
        prev_list.append(old_name)
    new_prev = ", ".join(prev_list)

    conn.execute("UPDATE users SET name = ?, username = ?, previous_names = ? WHERE id = ?", (new_name, new_name, new_prev, resolved_id))
    conn.commit()
    updated = conn.execute("SELECT * FROM users WHERE id = ?", (resolved_id,)).fetchone()
    conn.close()

    return jsonify({
        "success": True,
        "message": f"Name updated to {new_name}",
        "user": {
            "id": updated["id"],
            "name": updated["name"],
            "phone": updated["phone"],
            "balance": updated["balance"],
            "emergency_balance": updated["emergency_balance"],
            "previous_names": updated["previous_names"] if "previous_names" in updated.keys() else new_prev
        }
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

                is_bluetooth_offline = (
                    "BLUETOOTH" in str(t.get("channel", "")).upper() or
                    str(t_id).upper().startswith("BT-") or
                    str(t_id).upper().startswith("OFF-") or
                    t.get("is_offline") in (1, "1", True)
                )
                is_online_upi = not is_bluetooth_offline and (
                    str(t.get("channel", "")).upper() == "UPI" or
                    str(t_id).upper().startswith("UPI") or
                    "UPI" in note.upper()
                )
                channel_val = "UPI" if is_online_upi else "Bluetooth"
                method_val = "UPI" if is_online_upi else "BLUETOOTH"
                is_offline_val = 0 if is_online_upi else 1

                conn.execute("""
                    INSERT INTO transactions (tx_id, sender_id, receiver_id, payee, amount, status, channel, note, is_offline, method)
                    VALUES (?, ?, ?, ?, ?, 'completed', ?, ?, ?, ?)
                """, (str(t_id), sender_id, receiver_user_id or raw_receiver, payee, amount, channel_val, note, is_offline_val, method_val))

                reconciled_count += 1
                if not is_self_transfer and amount > 0:
                    # Bilateral Balance Adjustment:
                    # Debit sender's balance (online) or emergency balance (offline)
                    s_user = conn.execute("SELECT * FROM users WHERE id = ?", (sender_id,)).fetchone()
                    if s_user:
                        s_emg = float(s_user["emergency_balance"])
                        s_bal = float(s_user["balance"])
                        if is_online_upi:
                            s_bal = max(0.0, round(s_bal - amount, 2))
                        else:
                            if s_emg >= amount:
                                s_emg = round(s_emg - amount, 2)
                            else:
                                rem = amount - s_emg
                                s_emg = 0.0
                                s_bal = max(0.0, round(s_bal - rem, 2))
                        conn.execute("UPDATE users SET balance = ?, emergency_balance = ? WHERE id = ?", (s_bal, s_emg, sender_id))

                    # Credit receiver if receiver is a registered user
                    if receiver_user_id:
                        r_user = conn.execute("SELECT * FROM users WHERE id = ?", (receiver_user_id,)).fetchone()
                        if r_user:
                            if is_online_upi:
                                r_bal = round(float(r_user["balance"]) + amount, 2)
                                conn.execute("UPDATE users SET balance = ? WHERE id = ?", (r_bal, receiver_user_id))
                            else:
                                r_emg = round(float(r_user["emergency_balance"]) + amount, 2)
                                conn.execute("UPDATE users SET emergency_balance = ? WHERE id = ?", (r_emg, receiver_user_id))
                    elif payee and payee.strip() not in ("Unknown", ""):
                        # Auto-credit payee even if not registered on server yet
                        p_digits = "".join(filter(str.isdigit, payee))[-10:] if any(c.isdigit() for c in payee) else ""
                        p_phone = p_digits if len(p_digits) == 10 else f"98765{random.randint(10000, 99999)}"
                        conn.execute(
                            "INSERT INTO users (name, username, phone, balance, emergency_balance, previous_names) VALUES (?, ?, ?, ?, ?, '')",
                            (payee.strip(), payee.strip(), p_phone, 1000.0, 100.0 + amount)
                        )
                        conn.commit()

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
        "balance": updated_user["balance"] if updated_user else 1000.0,
        "emergency_balance": updated_user["emergency_balance"] if updated_user else 100.0,
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
