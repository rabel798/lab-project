from flask import Flask, render_template, request, jsonify

app = Flask(__name__)


@app.route("/")
def home():
    return render_template("main.html")

@app.route("/emergencypay")
def emergency_pay():
    return render_template("emergencypay.html")
    
@app.route("/insert", methods=["GET", "POST"])
def insert():
    if request.method == "POST":
        payee = request.form.get("payee") or (request.json.get("payee") if request.is_json else "Unknown")
        amount = request.form.get("amount") or (request.json.get("amount") if request.is_json else 0)
        note = request.form.get("note") or ""
        channel = request.form.get("channel") or "bluetooth"
        return jsonify({
            "status": "success",
            "message": "Payment recorded offline successfully",
            "data": {
                "payee": payee,
                "amount": amount,
                "note": note,
                "channel": channel
            }
        })
    return render_template("emergencypay.html")

if __name__ == "__main__":
    from waitress import serve
    print("waitress serving")
    serve(app, host="0.0.0.0", port=5000)