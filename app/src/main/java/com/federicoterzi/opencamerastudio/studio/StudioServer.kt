package com.federicoterzi.opencamerastudio.studio

import android.content.Intent
import android.support.v4.content.LocalBroadcastManager
import com.federicoterzi.opencamerastudio.MainActivity
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.support.v4.provider.DocumentFile
import android.webkit.MimeTypeMap
import java.io.IOException


class StudioServer(val mainActivity: MainActivity, val port : Int) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        val path = session.uri?.substring(1) ?: ""
        when (path) {
            "list" -> return listFiles()
            "download" -> {
                val filename = session.parameters["file"]
                if (filename != null) {
                    return downloadVideo(filename[0])
                }else{
                    return getNotFoundResponse()
                }
            }
            "start" -> {
                val name = session.parameters["name"]?.get(0) ?: ""
                val opt = JSONObject()
                opt.put("name", name)
                sendCommand("start", opt)
                return newFixedLengthResponse("OK")
            }
            "stop" -> {
                sendCommand("stop", null)
                return newFixedLengthResponse("OK")
            }
        }
//        val intent = Intent(MainActivity.STUDIO_BROADCAST_ID)
//        // You can also include some extra data.
//        intent.putExtra("data", "{\"type\":\"start\",\"name\":\""+path+"\"}");
//        LocalBroadcastManager.getInstance(mainActivity).sendBroadcast(intent)
//
//        Log.d("STUDIO", "Received")

        val cacheDir = mainActivity.cacheDir
        val websiteCacheDir = File(cacheDir, "website")
        return newFixedFileResponse(File(websiteCacheDir, "index.html"), "text/html")
    }

    fun listFiles(): Response {
        val storageDir = File(mainActivity.storageUtils.saveLocation)

        val resArray = JSONArray()

        storageDir.listFiles().forEach { file ->
            resArray.put(file.name)
        }

        val res = resArray.toString()+"\n"
        return newFixedLengthResponse(res)
    }

    fun downloadVideo(name: String): Response {
        val storageDir = File(mainActivity.storageUtils.saveLocation)

        val map = MimeTypeMap.getSingleton();
        val mime = map.getMimeTypeFromExtension(File(name).extension)
        return serveFile(mapOf(), File(storageDir, name), mime, true)
    }

    fun sendCommand(type: String, opt: JSONObject?) {
        val intent = Intent(MainActivity.STUDIO_BROADCAST_ID)
        val obj = JSONObject()
        obj.put("type", type)
        if (opt != null) {
            obj.put("opt", opt)
        }
        intent.putExtra("data", obj.toString());
        LocalBroadcastManager.getInstance(mainActivity).sendBroadcast(intent)
    }

    /**
     * Serves file from homeDir and its' subdirectories (only). Uses only URI,
     * ignores all headers and HTTP parameters.
     */
    //private Response serveFile(String uri, Map<String, String> header, DocumentFile file, String mime) {
    private fun serveFile(header: Map<String, String>, file: File, mime: String, forceDownload: Boolean): NanoHTTPD.Response {
        var res: NanoHTTPD.Response
        try {
            // Calculate etag
            //String etag = Integer.toHexString((file.getAbsolutePath() + file.lastModified() + "" + file.length()).hashCode());

            // Support (simple) skipping:
            var startFrom: Long = 0
            var endAt: Long = -1
            var range = header["range"]
            if (range != null) {
                if (range.startsWith("bytes=")) {
                    range = range.substring("bytes=".length)
                    val minus = range.indexOf('-')
                    try {
                        if (minus > 0) {
                            startFrom = java.lang.Long.parseLong(range.substring(0, minus))
                            endAt = java.lang.Long.parseLong(range.substring(minus + 1))
                        }
                    } catch (ignored: NumberFormatException) {
                    }

                }
            }

            // get if-range header. If present, it must match etag or else we
            // should ignore the range request
            val ifRange = header["if-range"]
            //boolean headerIfRangeMissingOrMatching = (ifRange == null || etag.equals(ifRange));
            val headerIfRangeMissingOrMatching = ifRange == null

            val ifNoneMatch = header["if-none-match"]
            //boolean headerIfNoneMatchPresentAndMatching = ifNoneMatch != null && ("*".equals(ifNoneMatch) || ifNoneMatch.equals(etag));
            val headerIfNoneMatchPresentAndMatching = ifNoneMatch != null && "*" == ifNoneMatch

            // Change return code and add Content-Range header when skipping is
            // requested
            val fileLen = file.length()

            if (headerIfRangeMissingOrMatching && range != null && startFrom >= 0 && startFrom < fileLen) {
                // range request that matches current etag
                // and the startFrom of the range is satisfiable
                if (headerIfNoneMatchPresentAndMatching) {
                    // range request that matches current etag
                    // and the startFrom of the range is satisfiable
                    // would return range from file
                    // respond with not-modified
                    res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_MODIFIED, mime, "")
                    //res.addHeader("ETag", etag);
                } else {
                    if (endAt < 0) {
                        endAt = fileLen - 1
                    }
                    var newLen = endAt - startFrom + 1
                    if (newLen < 0) {
                        newLen = 0
                    }

                    val fis = file.inputStream()

                    fis.skip(startFrom)

                    res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.PARTIAL_CONTENT, mime, fis, newLen)
                    res.addHeader("Accept-Ranges", "bytes")
                    res.addHeader("Content-Length", "" + newLen)
                    res.addHeader("Content-Range", "bytes $startFrom-$endAt/$fileLen")
                    //res.addHeader("ETag", etag);
                }
            } else {

                if (headerIfRangeMissingOrMatching && range != null && startFrom >= fileLen) {
                    // return the size of the file
                    // 4xx responses are not trumped by if-none-match
                    res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, NanoHTTPD.MIME_PLAINTEXT, "")
                    res.addHeader("Content-Range", "bytes */$fileLen")
                    //res.addHeader("ETag", etag);
                } else if (range == null && headerIfNoneMatchPresentAndMatching) {
                    // full-file-fetch request
                    // would return entire file
                    // respond with not-modified
                    res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_MODIFIED, mime, "")
                    //res.addHeader("ETag", etag);
                } else if (!headerIfRangeMissingOrMatching && headerIfNoneMatchPresentAndMatching) {
                    // range request that doesn't match current etag
                    // would return entire (different) file
                    // respond with not-modified

                    res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_MODIFIED, mime, "")
                    //res.addHeader("ETag", etag);
                } else {
                    // supply the file
                    res = newFixedFileResponse(file, mime)
                    res.addHeader("Content-Length", "" + fileLen)
                    //res.addHeader("ETag", etag);
                }
            }
        } catch (ioe: IOException) {
            res = getForbiddenResponse("Reading file failed.")
        }

        if (!forceDownload) {
            res.addHeader("Content-Disposition", "inline; filename=\"" + file.name + "\"")
        } else {
            res.addHeader("Content-Disposition", "attachment; filename=\"" + file.name + "\"")
        }
        res.addHeader("Access-Control-Allow-Origin", "*")
        return res
    }

    private fun newFixedFileResponse(file: File, mime: String): NanoHTTPD.Response {
        val inputStream = file.inputStream()
        val res: NanoHTTPD.Response
        res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, mime, inputStream, file.length())
        res.addHeader("Accept-Ranges", "bytes")
        return res
    }

    private fun getNotFoundResponse(): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Error 404")
    }

    private fun getErrorResponse(): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Internal Error")
    }

    private fun getForbiddenResponse(s: String): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "FORBIDDEN: $s")
    }
}