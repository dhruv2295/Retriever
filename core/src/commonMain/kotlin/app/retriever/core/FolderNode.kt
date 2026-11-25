package app.retriever.core

/**
 * A folder one level under the folder being shown. [name] is its last part of [path], or several parts when folders
 * holding nothing but one subfolder were joined ("media/com.whatsapp"); empty for files at the top level. [files] counts
 * every file under it, its subfolders' too.
 */
data class FolderNode(val path: String, val name: String, val files: Long)

/**
 * The folders directly under [parent] (null: the top), A to Z, from per-folder file counts such as [Store.folders]. A
 * folder with no files of its own appears when files sit further down; one that holds just a single subfolder is joined
 * with it, so a deep app folder takes one tap, not five.
 */
fun folderChildren(counts: List<Pair<String, Long>>, parent: String?): List<FolderNode> {
    val prefix = if (parent == null) "" else "$parent/"
    val below = counts.filter { (path, _) -> path.startsWith(prefix) && path != parent }
    // At the top, files with no folder at all are a node of their own, named by the app ("Top level").
    val top = if (parent == null) below.filter { it.first.isEmpty() } else emptyList()
    val nodes = (below - top.toSet()).groupBy { (path, _) -> prefix + path.removePrefix(prefix).substringBefore('/') }
        .map { (path, group) -> join(counts, FolderNode(path, path.removePrefix(prefix), group.sumOf { it.second })) }
    return top.map { FolderNode("", "", it.second) } + nodes.sortedBy { it.name.lowercase() }
}

private tailrec fun join(counts: List<Pair<String, Long>>, node: FolderNode): FolderNode {
    if (counts.any { it.first == node.path }) return node
    val only = folderChildren(counts, node.path).singleOrNull() ?: return node
    return join(counts, FolderNode(only.path, node.name + "/" + only.name, node.files))
}
