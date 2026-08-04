from flask import Flask, render_template

app = Flask(__name__)


@app.route("/")
def home():
    return render_template("main.html")
    
@app.route("/insert", methods=["POST"])
def insert():
    return render_template("insert.html")

if __name__ == "__main__":
    from waitress import serve
    print ("waitress serving")
    serve(app,host="0.0.0.0", port=5000)