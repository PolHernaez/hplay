package com.hplay.app.discovery

import org.w3c.dom.Element
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/** Descripción de dispositivo UPnP (description.xml): nombre, fabricante y URLs de control. */
data class UpnpDescription(
    val friendlyName: String,
    val manufacturer: String,
    val model: String,
    val deviceType: String,
    val services: Map<String, String>, // serviceType sin versión → controlURL absoluta
) {
    val avTransport get() = services["urn:schemas-upnp-org:service:AVTransport"]
    val renderingControl get() = services["urn:schemas-upnp-org:service:RenderingControl"]
    val connectionManager get() = services["urn:schemas-upnp-org:service:ConnectionManager"]

    companion object {
        fun parse(xml: String, location: String): UpnpDescription {
            val f = DocumentBuilderFactory.newInstance()
            // Sin DTD externas (XXE). En Android estas funciones pueden no existir: se ignora.
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { f.setExpandEntityReferences(false) }
            val doc = f.newDocumentBuilder().parse(xml.byteInputStream())
            fun first(tag: String) = doc.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
            val base = first("URLBase").ifBlank { location }
            val services = HashMap<String, String>()
            val list = doc.getElementsByTagName("service")
            for (i in 0 until list.length) {
                val e = list.item(i) as? Element ?: continue
                fun child(t: String) = e.getElementsByTagName(t).item(0)?.textContent?.trim().orEmpty()
                val type = child("serviceType").substringBeforeLast(':')
                val ctl = child("controlURL")
                if (type.isNotBlank() && ctl.isNotBlank() && type !in services) {
                    services[type] = runCatching { URL(URL(base), ctl).toString() }.getOrDefault(ctl)
                }
            }
            return UpnpDescription(first("friendlyName"), first("manufacturer"), first("modelName"), first("deviceType"), services)
        }
    }
}
