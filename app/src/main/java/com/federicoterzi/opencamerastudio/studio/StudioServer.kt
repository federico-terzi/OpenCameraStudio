package com.federicoterzi.opencamerastudio.studio

import com.federicoterzi.opencamerastudio.MainActivity
import fi.iki.elonen.NanoHTTPD
import android.support.v4.content.LocalBroadcastManager
import android.content.Intent
import android.util.Log


class StudioServer(val mainActivity: MainActivity, val port : Int) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession?): Response {
        val path = session?.uri?.substring(1) ?: ""
        val intent = Intent(MainActivity.STUDIO_BROADCAST_ID)
        // You can also include some extra data.
        intent.putExtra("data", "{\"type\":\"start\",\"name\":\""+path+"\"}");
        LocalBroadcastManager.getInstance(mainActivity).sendBroadcast(intent)

        Log.d("STUDIO", "Received")

        var msg = "<html><body><h1>Hello server</h1>\n"
        msg += "<p>We serve " + session?.getUri() + " !</p>"
        return NanoHTTPD.newFixedLengthResponse("$msg</body></html>\n")
    }
}