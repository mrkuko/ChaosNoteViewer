import QtQuick
import ChaosNotes
import "../Crypto.js" as Crypto

// The shared app logic (notes, account, encryption key, sync) with this build's source of random numbers.
// The Plasma widget does the same with /dev/urandom, see plasmoid/contents/ui/main.qml.
AppController {
    entropy: function (n, callback) {
        const bytes = Crypto.fromHex(SecureRandom.hex(n), n)
        if (bytes === null)
            callback({ code: "RANDOM", message: "The system did not give random numbers." }, null)
        else
            callback(null, bytes)
    }
}
