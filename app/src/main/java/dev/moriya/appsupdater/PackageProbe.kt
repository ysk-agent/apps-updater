package dev.moriya.appsupdater

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.Inflater

/**
 * Package learning (v0.2.1): read an APK's package name out of the
 * release asset itself, with HTTP Range requests, instead of
 * relying only on the hardcoded [Store.KNOWN_PACKAGES] map.
 *
 * Why: the map is curated by hand, and the two newest projects
 * (findmy-mac.apk, yt-client.apk) were never added to it, so their
 * rows could only show 「パッケージ: ダウンロード後に判明」 — no
 * installed version, no update judgement — until the whole APK
 * (72 MB for findmy-mac) had been downloaded. The package name is
 * a fact of the APK, so the APK is where the app now reads it:
 *
 *   1. fetch the file tail and locate the ZIP End Of Central
 *      Directory record (the central directory usually comes with
 *      it; if not, fetch the missing head of the directory),
 *   2. walk the central directory to the `AndroidManifest.xml`
 *      entry (local header offset, method, compressed size),
 *   3. fetch the local header (a 256-byte probe), then exactly the
 *      entry's compressed bytes, inflating when deflated,
 *   4. parse the binary Android XML: string pool, then the root
 *      `manifest` element's `package` attribute.
 *
 * Redirects are followed MANUALLY (instanceFollowRedirects=false,
 * Location re-requested with the Range header) so the Range is
 * guaranteed to reach the storage host behind GitHub's redirect.
 * Hard limits per file: at most [MAX_REQUESTS] requests and
 * [MAX_BYTES] bytes in total. A ranged request answered with
 * HTTP 200 (Range ignored) aborts the probe on the spot — a whole
 * APK is never downloaded for this. Any failure returns a null
 * package and the row behaves exactly as in v0.2.0. The learned
 * value is display/judgement input only; the download and install
 * paths keep verifying the real downloaded APK as before.
 *
 * This fetches ranges of the APK asset only — never update.json
 * (removed from the scan in v0.2.0, deliberately not brought back).
 */
object PackageProbe {

    /** Probe outcome; [packageName] is null on any failure.
     *  [requests] and [bytes] are the probe's actual network cost,
     *  reported by the caller for the scan log. */
    class Outcome(val packageName: String?, val requests: Int, val bytes: Long)

    private const val MAX_REQUESTS = 8
    private const val MAX_BYTES = 512L * 1024

    /** First tail fetch: the EOCD record lives in the last
     *  65,557 bytes (22-byte record + max comment); 128 KiB also
     *  swallows the whole central directory of every current
     *  project (the largest is ~88 KiB), so the directory walk
     *  normally needs no second fetch. */
    private const val TAIL_BYTES = 128 * 1024 + 22
    private const val HEADER_PROBE_BYTES = 256
    private const val MAX_MANIFEST_COMPRESSED = 256 * 1024
    private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024

    private val PACKAGE_NAME =
        Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")

    /** The package name of the APK at [downloadUrl] (whose total
     *  size the releases API already reported as [totalSize]), or
     *  an outcome with a null package on any failure. Never throws. */
    fun probe(downloadUrl: String, totalSize: Long): Outcome {
        val fetcher = RangeFetcher(downloadUrl)
        val pkg = try {
            if (totalSize > 0) fetch(fetcher, totalSize) else null
        } catch (e: Exception) {
            null
        }
        return Outcome(pkg, fetcher.requests, fetcher.bytes)
    }

    private fun fetch(fetcher: RangeFetcher, totalSize: Long): String? {
        // -- tail + End Of Central Directory -------------------------
        val tailLen = minOf(totalSize, TAIL_BYTES.toLong())
        val tailStart = totalSize - tailLen
        val tail = fetcher.get(tailStart, totalSize - 1) ?: return null
        var eocd = -1
        var i = tail.size - 22
        while (i >= 0) {
            if (u32(tail, i) == 0x06054B50L) {
                val commentLen = u16(tail, i + 20)
                if (tailStart + i + 22 + commentLen == totalSize) {
                    eocd = i
                    break
                }
            }
            i--
        }
        if (eocd < 0) return null
        if (u16(tail, eocd + 10) == 0xFFFF) return null // ZIP64: bail
        val cdSize = u32(tail, eocd + 12)
        val cdOff = u32(tail, eocd + 16)
        if (cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) return null
        val eocdAbs = tailStart + eocd
        // The APK signing block sits BEFORE the central directory,
        // so the directory still ends exactly where the EOCD begins.
        if (cdOff + cdSize != eocdAbs) return null

        // -- central directory ----------------------------------------
        val cd: ByteArray = if (cdOff >= tailStart) {
            tail.copyOfRange((cdOff - tailStart).toInt(), (cdOff - tailStart + cdSize).toInt())
        } else {
            val head = fetcher.get(cdOff, tailStart - 1) ?: return null
            head + tail.copyOfRange(0, (cdSize - head.size).toInt())
        }
        var method = -1
        var compSize = -1L
        var localOff = -1L
        var p = 0
        while (p + 46 <= cd.size && u32(cd, p) == 0x02014B50L) {
            val m = u16(cd, p + 10)
            val cs = u32(cd, p + 20)
            val fnl = u16(cd, p + 28)
            val exl = u16(cd, p + 30)
            val cml = u16(cd, p + 32)
            val lho = u32(cd, p + 42)
            if (p + 46 + fnl > cd.size) return null
            val name = String(cd, p + 46, fnl, Charsets.UTF_8)
            if (name == "AndroidManifest.xml") {
                method = m
                compSize = cs
                localOff = lho
                break
            }
            p += 46 + fnl + exl + cml
        }
        if (method < 0) return null
        if (compSize < 0 || compSize > MAX_MANIFEST_COMPRESSED) return null
        if (localOff < 0 || localOff == 0xFFFFFFFFL) return null

        // -- local header + entry data --------------------------------
        val probeEnd = minOf(totalSize - 1, localOff + HEADER_PROBE_BYTES - 1)
        val probe = fetcher.get(localOff, probeEnd) ?: return null
        if (probe.size < 30 || u32(probe, 0) != 0x04034B50L) return null
        val dataOff = localOff + 30 + u16(probe, 26) + u16(probe, 28)
        val data: ByteArray = if (dataOff + compSize - 1 <= probeEnd) {
            val from = (dataOff - localOff).toInt()
            probe.copyOfRange(from, from + compSize.toInt())
        } else {
            fetcher.get(dataOff, dataOff + compSize - 1) ?: return null
        }
        val xml = when (method) {
            0 -> data
            8 -> inflate(data) ?: return null
            else -> return null
        }
        val pkg = parseManifestPackage(xml) ?: return null
        return if (pkg.length <= 255 && PACKAGE_NAME.matches(pkg)) pkg else null
    }

    private fun inflate(data: ByteArray): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) return null
                } else {
                    out.write(buf, 0, n)
                    if (out.size() > MAX_MANIFEST_BYTES) return null
                }
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    // -- binary Android XML -------------------------------------------
    //
    // Minimal reader for exactly one job: the string pool, then the
    // root element's `package` attribute. Chunk layout follows
    // ResChunk_header / ResStringPool_header / ResXMLTree_node /
    // ResXMLTree_attrExt / ResXMLTree_attribute.

    private fun parseManifestPackage(data: ByteArray): String? {
        if (data.size < 8 || u16(data, 0) != 0x0003) return null
        val total = minOf(u32(data, 4), data.size.toLong()).toInt()
        var off = u16(data, 2)
        var strings: List<String>? = null
        while (off + 8 <= total) {
            val type = u16(data, off)
            val size = u32(data, off + 4)
            if (size < 8 || size > total - off) return null
            when (type) {
                0x0001 -> strings = parseStringPool(data, off)
                0x0102 -> {
                    val pool = strings ?: return null
                    return readRootPackage(data, off, pool)
                }
            }
            off += size.toInt()
        }
        return null
    }

    private fun parseStringPool(data: ByteArray, off: Int): List<String>? {
        val headerSize = u16(data, off + 2)
        val stringCount = u32(data, off + 8)
        if (stringCount > 100_000) return null
        val flags = u32(data, off + 16)
        val stringsStart = u32(data, off + 20)
        val utf8 = flags and 0x100L != 0L
        val out = ArrayList<String>(stringCount.toInt())
        for (idx in 0 until stringCount.toInt()) {
            val rel = u32(data, off + headerSize + 4 * idx)
            var p = (off + stringsStart + rel).toInt()
            if (utf8) {
                // Skip the UTF-16 char count (1-2 bytes), then the
                // byte count (1-2 bytes), then the bytes themselves.
                var b = data[p].toInt() and 0xFF; p++
                if (b and 0x80 != 0) p++
                b = data[p].toInt() and 0xFF; p++
                val byteLen: Int
                if (b and 0x80 != 0) {
                    byteLen = ((b and 0x7F) shl 8) or (data[p].toInt() and 0xFF); p++
                } else {
                    byteLen = b
                }
                out.add(String(data, p, byteLen, Charsets.UTF_8))
            } else {
                var len = u16(data, p); p += 2
                if (len and 0x8000 != 0) {
                    len = ((len and 0x7FFF) shl 16) or u16(data, p); p += 2
                }
                out.add(String(data, p, len * 2, Charsets.UTF_16LE))
            }
        }
        return out
    }

    private fun readRootPackage(data: ByteArray, off: Int, pool: List<String>): String? {
        // ResXMLTree_node (16 bytes) then ResXMLTree_attrExt:
        // ns u32, name u32, attributeStart u16, attributeSize u16,
        // attributeCount u16, ...
        if (pool.getOrNull(u32(data, off + 20).toInt()) != "manifest") return null
        val attrStart = u16(data, off + 24)
        val attrSize = u16(data, off + 26)
        val attrCount = u16(data, off + 28)
        if (attrSize < 20) return null
        for (n in 0 until attrCount) {
            val a = off + 16 + attrStart + n * attrSize
            if (a + 20 > data.size) return null
            if (pool.getOrNull(u32(data, a + 4).toInt()) != "package") continue
            val raw = u32(data, a + 8)
            if (raw != 0xFFFFFFFFL) return pool.getOrNull(raw.toInt())
            // No raw value: the typed value must be a string (0x03),
            // whose data is the string-pool index.
            if ((data[a + 15].toInt() and 0xFF) == 0x03) {
                return pool.getOrNull(u32(data, a + 16).toInt())
            }
            return null
        }
        return null
    }

    // -- little-endian readers (bounds violations throw, and the
    //    caller's probe() turns any exception into a failed probe) ---

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    // -- ranged GET with manual redirects -------------------------------

    /**
     * Range GETs against one asset. The first request goes to
     * GitHub's download URL, which answers 302; the Location is
     * remembered and re-requested with the same Range header, so
     * the storage host actually sees it. A 200 answer means the
     * Range was ignored somewhere: the probe aborts immediately
     * and never reads the body.
     */
    private class RangeFetcher(startUrl: String) {
        private var url: String = startUrl
        var requests: Int = 0
            private set
        var bytes: Long = 0
            private set
        private var rangeIgnored = false

        /** Exactly the bytes of [start, endInclusive], or null on
         *  any failure (including budget exhaustion). */
        fun get(start: Long, end: Long): ByteArray? {
            while (true) {
                if (rangeIgnored || requests >= MAX_REQUESTS) return null
                if (bytes + (end - start + 1) > MAX_BYTES) return null
                var conn: HttpURLConnection? = null
                try {
                    requests++
                    conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 15_000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "AppsUpdater")
                        setRequestProperty("Accept", "application/octet-stream")
                        setRequestProperty("Range", "bytes=$start-$end")
                    }
                    when (val code = conn.responseCode) {
                        in 300..399 -> {
                            val location = conn.getHeaderField("Location") ?: return null
                            // Do not read the body; just follow.
                            url = URL(URL(url), location).toString()
                        }
                        200 -> {
                            rangeIgnored = true
                            return null
                        }
                        206 -> {
                            val want = (end - start + 1).toInt()
                            val body = conn.inputStream.use { input ->
                                val out = ByteArrayOutputStream(want)
                                val buf = ByteArray(32 * 1024)
                                while (out.size() < want) {
                                    val n = input.read(
                                        buf, 0, minOf(buf.size, want - out.size())
                                    )
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                }
                                out.toByteArray()
                            }
                            if (body.size != want) return null
                            bytes += body.size
                            return body
                        }
                        else -> return null
                    }
                } catch (e: Exception) {
                    return null
                } finally {
                    conn?.disconnect()
                }
            }
        }
    }
}
