package app.retriever.core

// ART and HotSpot vectorise dotInt8 (verified with oatdump), so the scan keeps its per-row loop here.
internal actual val dotRows: RowScorer? = null

internal actual val cpuCores: Int = Runtime.getRuntime().availableProcessors()
