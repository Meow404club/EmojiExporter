package top.brokestar.emojiexporter.lsposed.http

import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response

interface HttpHandler {
    fun handle(session: IHTTPSession, body: String? = null): Response
}
