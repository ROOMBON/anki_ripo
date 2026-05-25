package com.example.data

import org.json.JSONArray
import org.json.JSONObject

data class RepoNode(
    val name: String,
    val type: String, // "directory" or "file"
    val children: List<RepoNode> = emptyList()
) {
    val isDirectory: Boolean get() = type == "directory"
    val isFile: Boolean get() = type == "file"
}

object RepoParser {
    fun parseJson(jsonString: String): RepoNode? {
        if (jsonString.isBlank()) return null
        return try {
            val rootObj = JSONObject(jsonString)
            parseNode(rootObj)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseNode(obj: JSONObject): RepoNode {
        val name = obj.optString("name", "")
        val type = obj.optString("type", "directory")
        val childrenList = mutableListOf<RepoNode>()
        if (obj.has("children")) {
            val arr = obj.optJSONArray("children")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val childObj = arr.optJSONObject(i)
                    if (childObj != null) {
                        childrenList.add(parseNode(childObj))
                    }
                }
            }
        }
        return RepoNode(name, type, childrenList)
    }

    /**
     * Traverses the tree to find all files (.txt) inside the specified directory path.
     * Path elements are slash separated, e.g. "מאגר השאלות/שס/ברכות"
     */
    fun findFilesUnderFolder(root: RepoNode, folderPath: String): List<String> {
        val targetNode = findNodeByPath(root, folderPath) ?: return emptyList()
        val files = mutableListOf<String>()
        collectFiles(targetNode, folderPath, files)
        return files.sorted()
    }

    private fun findNodeByPath(root: RepoNode, path: String): RepoNode? {
        val parts = path.split("/").filter { it.isNotBlank() }
        if (parts.isEmpty()) return root

        // If the path starts with the root's name, start searching from its children
        var current: RepoNode = root
        val startIndex = if (parts[0].equals(root.name, ignoreCase = true)) 1 else 0

        for (i in startIndex until parts.size) {
            val part = parts[i]
            val nextNode = current.children.find { it.name.trim().equals(part.trim(), ignoreCase = true) }
            if (nextNode != null) {
                current = nextNode
            } else {
                return null
            }
        }
        return current
    }

    private fun collectFiles(node: RepoNode, currentPath: String, result: MutableList<String>) {
        if (node.isFile) {
            if (node.name.endsWith(".txt")) {
                result.add(currentPath)
            }
        } else {
            for (child in node.children) {
                val nextPath = if (currentPath.isEmpty()) child.name else "$currentPath/${child.name}"
                collectFiles(child, nextPath, result)
            }
        }
    }
}
