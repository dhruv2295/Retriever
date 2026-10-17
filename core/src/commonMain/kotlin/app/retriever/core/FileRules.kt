package app.retriever.core

/** Which files count and what kind they are; shared by the Android and iOS sources. */
object FileRules {
    // Android/media holds WhatsApp and other apps' shared media (most photos on a typical phone);
    // only Android/data and Android/obb are private to their apps.
    fun shouldSkip(relativePath: String, name: String): Boolean =
        relativePath.startsWith("Android/data/", ignoreCase = true) ||
            relativePath.startsWith("Android/obb/", ignoreCase = true) ||
            relativePath.contains("/.") ||
            relativePath.startsWith(".") ||
            name.startsWith(".")

    val DOC_TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "json", "xml", "html", "htm", "kt", "java", "py", "js", "ts", "swift",
        "go", "rs", "c", "cpp", "h", "yaml", "yml", "log", "docx", "pptx", "xlsx",
        "odt", "ods", "odp", "epub", "rtf",
    )

    private val MIME_BY_EXTENSION = mapOf(
        "md" to "text/markdown",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "text/xml",
        "html" to "text/html",
        "htm" to "text/html",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "epub" to "application/epub+zip",
        "rtf" to "text/rtf",
        "pdf" to "application/pdf",
    )

    /** For files whose MIME type MediaStore left empty; null when the extension is unknown. */
    fun mimeForExtension(ext: String): String? {
        val e = ext.lowercase()
        return MIME_BY_EXTENSION[e] ?: if (e in DOC_TEXT_EXTENSIONS) "text/plain" else null
    }

    fun classify(mime: String, relativePath: String, name: String): Kind = when {
        mime.startsWith("image/") ->
            if (relativePath.contains("screenshot", ignoreCase = true) ||
                name.contains("screenshot", ignoreCase = true)
            ) Kind.SCREENSHOT else Kind.PHOTO
        mime.startsWith("video/") -> Kind.VIDEO
        mime.startsWith("audio/") -> Kind.AUDIO
        mime == "application/pdf" -> Kind.PDF
        isDoc(mime, name) -> Kind.DOC
        else -> Kind.OTHER
    }

    private fun isDoc(mime: String, name: String): Boolean =
        mime.startsWith("text/") ||
            mime == "application/json" ||
            mime.contains("officedocument") ||
            mime.contains("opendocument") ||
            mime.contains("msword") ||
            mime == "application/epub+zip" ||
            name.substringAfterLast('.', "").lowercase() in DOC_TEXT_EXTENSIONS

    /** The newest [quotaPerKind] files of each kind. */
    fun <T> pickSamples(
        files: List<T>,
        quotaPerKind: Map<Kind, Int>,
        kind: (T) -> Kind,
        modifiedAt: (T) -> Long,
    ): List<T> = files.groupBy(kind).flatMap { (k, group) ->
        group.sortedByDescending(modifiedAt).take(quotaPerKind[k] ?: 0)
    }
}
