package app.retriever.core

import java.text.NumberFormat

actual fun formatCount(n: Long): String = NumberFormat.getIntegerInstance().format(n)
