.pragma library
.import "FirebaseConfig.js" as Config

// Firebase Authentication over its REST API (there is no Firebase SDK for QML).
// Every call is asynchronous: done(error, result). error is null on success, otherwise
// { code, message } where code is Firebase's code ("EMAIL_EXISTS", ...) or "NETWORK".
// result: { uid, email, idToken, refreshToken }.

function _request(url, contentType, body, done) {
    var xhr = new XMLHttpRequest()
    xhr.timeout = 15000
    xhr.onreadystatechange = function () {
        if (xhr.readyState !== XMLHttpRequest.DONE)
            return
        var data = null
        try { data = JSON.parse(xhr.responseText) } catch (e) { }
        if (xhr.status >= 200 && xhr.status < 300 && data) {
            done(null, data)
        } else if (xhr.status === 0 || !data || !data.error) {
            done({ code: "NETWORK", message: errorText("NETWORK") }, null)
        } else {
            var code = String(data.error.message || "UNKNOWN").split(" ")[0]
            done({ code: code, message: errorText(code) }, null)
        }
    }
    xhr.open("POST", url)
    xhr.setRequestHeader("Content-Type", contentType)
    xhr.send(body)
}

function _account(email, password, endpoint, done) {
    _request(Config.authUrl + "/accounts:" + endpoint + "?key=" + encodeURIComponent(Config.apiKey),
        "application/json", JSON.stringify({ email: email, password: password, returnSecureToken: true }),
        function (err, d) {
            done(err, err ? null : { uid: d.localId, email: d.email, idToken: d.idToken, refreshToken: d.refreshToken })
        })
}

function signUp(email, password, done) { _account(email, password, "signUp", done) }
function signIn(email, password, done) { _account(email, password, "signInWithPassword", done) }

// Exchanges the stored refresh token for a fresh id token (they last an hour).
function refresh(refreshToken, done) {
    _request(Config.tokenUrl + "/token?key=" + encodeURIComponent(Config.apiKey),
        "application/x-www-form-urlencoded",
        "grant_type=refresh_token&refresh_token=" + encodeURIComponent(refreshToken),
        function (err, d) {
            done(err, err ? null : { uid: d.user_id, idToken: d.id_token, refreshToken: d.refresh_token })
        })
}

function _oob(body, done) {
    _request(Config.authUrl + "/accounts:sendOobCode?key=" + encodeURIComponent(Config.apiKey),
        "application/json", JSON.stringify(body), function (err) { done(err) })
}

// Emails the signed-in user a link to confirm the address.
function sendVerification(idToken, done) { _oob({ requestType: "VERIFY_EMAIL", idToken: idToken }, done) }

// Emails a link to choose a new password. Works without being signed in.
function sendPasswordReset(email, done) { _oob({ requestType: "PASSWORD_RESET", email: email }, done) }

// Claims inside an id token (a JWT), e.g. email_verified. Only for the UI: Google signs the token and
// the Firestore rules check those claims on the server, so editing this client can't get around them.
function claims(idToken) {
    try {
        var part = idToken.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")
        while (part.length % 4)
            part += "="
        var bytes = Qt.atob(part)
        var escaped = ""
        for (var i = 0; i < bytes.length; ++i)
            escaped += "%" + ("0" + bytes.charCodeAt(i).toString(16)).slice(-2)
        return JSON.parse(decodeURIComponent(escaped))
    } catch (e) {
        return {}
    }
}

function isVerified(idToken) {
    return claims(idToken).email_verified === true
}

// The refresh token is no longer usable: the user must sign in again (as opposed to being offline).
function isSessionLost(error) {
    return ["TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN", "USER_NOT_FOUND", "USER_DISABLED", "CREDENTIAL_TOO_OLD_LOGIN_AGAIN"]
        .indexOf(error.code) >= 0
}

function errorText(code) {
    switch (code) {
    case "EMAIL_EXISTS": return "An account with this email already exists."
    case "INVALID_EMAIL": return "This email address is not valid."
    case "MISSING_EMAIL": return "Enter your email."
    case "MISSING_PASSWORD": return "Enter your password."
    case "WEAK_PASSWORD": return "The password must have at least 6 characters."
    case "INVALID_LOGIN_CREDENTIALS":
    case "INVALID_PASSWORD":
    case "EMAIL_NOT_FOUND": return "Wrong email or password."
    case "USER_DISABLED": return "This account has been disabled."
    case "TOO_MANY_ATTEMPTS_TRY_LATER": return "Too many attempts, try again later."
    case "OPERATION_NOT_ALLOWED": return "Email/password sign-in is not enabled for this Firebase project."
    case "RESET_EMAIL_NOT_FOUND": return "No account with this email."
    case "NOT_VERIFIED": return "The email is not verified yet. Open the link in the email first."
    case "NETWORK": return "Could not reach Firebase. Check your connection."
    }
    return "Sign-in failed (" + code + ")."
}
