package com.atrium.launcher

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.view.View

/** One row in universal search. */
class Result(
    val title: String,
    val subtitle: String? = null,
    val score: Int = 0,
    val run: (View?) -> Unit,
)

/** A category of results. Providers are registered in priority order. */
interface SearchProvider {
    val id: String
    val title: String
    fun query(q: String, limit: Int): List<Result>
}

class Column(val id: String, val title: String, val results: List<Result>)

class AppSearch(private val index: AppIndex) : SearchProvider {
    override val id = "apps"
    override val title = "Apps"

    override fun query(q: String, limit: Int): List<Result> {
        val trimmed = q.trim()
        val allApps = index.apps
        if (allApps.isEmpty()) return emptyList()

        if (trimmed.isEmpty()) {
            // When query is empty, return all installed apps sorted alphabetically
            return allApps.map { a -> Result(a.label, score = 0) { v -> index.launch(a, v) } }
        }

        val scored = ArrayList<Pair<Int, AppEntry>>()
        for (a in allApps) {
            val score = Fzf.score(trimmed, a.label) ?: Fzf.score(trimmed, a.key)
            if (score != null) scored += score to a
        }
        scored.sortWith(compareByDescending<Pair<Int, AppEntry>> { it.first }.thenBy { it.second.key })
        return scored.take(limit).map { (score, a) ->
            Result(a.label, score = score) { v -> index.launch(a, v) }
        }
    }
}

class ContactsSearch(
    private val context: Context,
    private val settings: LauncherSettings,
) : SearchProvider {
    override val id = "contacts"
    override val title = "Contacts"

    override fun query(q: String, limit: Int): List<Result> {
        val trimmed = q.trim()
        if (!settings.contactsInSearch) return emptyList()
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val results = ArrayList<Pair<Int, Result>>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
        )

        try {
            val cursor: Cursor? = context.contentResolver.query(
                uri, projection, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )
            cursor?.use { c ->
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val seen = HashSet<String>()

                while (c.moveToNext()) {
                    val name = if (nameIdx != -1) c.getString(nameIdx) ?: "" else ""
                    val number = if (numIdx != -1) c.getString(numIdx) ?: "" else ""
                    val cleanNum = number.replace(Regex("[^0-9+]"), "")

                    if (name.isEmpty() || cleanNum.isEmpty() || !seen.add("$name|$cleanNum")) continue

                    val res = Result(name, number, score = 0) {
                        try {
                            val callIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNum")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(callIntent)
                        } catch (_: Exception) {
                        }
                    }

                    if (trimmed.isEmpty()) {
                        results += 0 to res
                    } else {
                        val nameScore = Fzf.score(trimmed, name)
                        val numScore = if (nameScore == null && trimmed.any { it.isDigit() }) {
                            if (cleanNum.contains(trimmed)) 50 else null
                        } else null

                        val finalScore = nameScore ?: numScore
                        if (finalScore != null) {
                            results += finalScore to Result(name, number, score = finalScore, run = res.run)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (trimmed.isEmpty()) {
            results.sortBy { it.second.title.lowercase(java.util.Locale.ROOT) }
            return results.map { it.second }
        }

        results.sortWith(compareByDescending<Pair<Int, Result>> { it.first }.thenBy { it.second.title })
        return results.take(limit).map { it.second }
    }
}

class WebSearch(
    private val settings: LauncherSettings,
    private val go: (String, View?) -> Unit,
) : SearchProvider {
    override val id = "web"
    override val title = "Web"

    override fun query(q: String, limit: Int): List<Result> {
        val t = q.trim()
        val engine = engineName(settings.webEngine)
        return if (t.isEmpty()) {
            listOf(Result("Search the web", engine, score = 0) { v -> go("", v) })
        } else {
            listOf(Result("Search \u201C$t\u201D", engine, score = 1) { v -> go(t, v) })
        }
    }
}

fun engineName(index: Int): String = when (index) {
    1 -> "DuckDuckGo"
    2 -> "Brave Search"
    else -> "Google"
}

class SearchEngine(private val providers: List<SearchProvider>) {
    /**
     * Best fuzzy match wins center column across categories.
     * If tied scores or same name: Contacts > Apps > Web.
     * If no matches in apps or contacts: Web wins center column.
     * If query is empty: Default order [apps, contacts, web].
     */
    fun query(q: String, limit: Int): List<Column> {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) {
            val out = ArrayList<Column>(providers.size)
            for (p in providers) {
                val r = p.query(trimmed, 200)
                if (r.isNotEmpty()) out += Column(p.id, p.title, r)
            }
            return out
        }

        val scored = ArrayList<Triple<Column, Int, Int>>() // col, maxScore, tiePriority
        for (p in providers) {
            val r = p.query(trimmed, limit)
            if (r.isNotEmpty()) {
                val maxScore = r.maxOf { it.score }
                // Category priority tie-breaker: contacts (2) > apps (1) > web (0)
                val tiePriority = when (p.id) {
                    "contacts" -> 2
                    "apps" -> 1
                    else -> 0
                }
                scored += Triple(Column(p.id, p.title, r), maxScore, tiePriority)
            }
        }

        scored.sortWith(
            compareByDescending<Triple<Column, Int, Int>> { it.second }
                .thenByDescending { it.third }
        )

        return scored.map { it.first }
    }
}

fun webIntent(q: String, engine: Int = 0): Intent {
    val query = q.trim()
    val url = when (engine) {
        1 -> if (query.isEmpty()) "https://duckduckgo.com" else "https://duckduckgo.com/?q=" + Uri.encode(query)
        2 -> if (query.isEmpty()) "https://search.brave.com" else "https://search.brave.com/search?q=" + Uri.encode(query)
        else -> if (query.isEmpty()) "https://www.google.com" else "https://www.google.com/search?q=" + Uri.encode(query)
    }
    return Intent(Intent.ACTION_VIEW, Uri.parse(url))
}
