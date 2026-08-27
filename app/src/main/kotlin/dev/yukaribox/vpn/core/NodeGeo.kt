package dev.yukaribox.vpn.core

/**
 * Works out which country a node is in, from the only evidence a share link carries:
 * the label its author typed and the hostname it dials.
 *
 * Subscription feeds name nodes for humans, not for parsers — `🇯🇵 JP-Tokyo-01`,
 * `Netherlands 03`, `us-east.example.com`, `Германия | Frankfurt` — so this reads all
 * three of the conventions that actually appear: a regional-indicator flag emoji, a
 * country or city name in English or Russian, and a bare ISO code used as a prefix.
 *
 * Pure and Android-free so the matching rules are unit-tested rather than discovered
 * by staring at a list of plates. Returns `null` rather than guessing: the UI then
 * falls back to the protocol tag, which is honest, where a wrong flag is not.
 */
object NodeGeo {

    /** First regional-indicator code point (the "A" of a flag emoji pair). */
    private const val FLAG_A = 0x1F1E6

    /** Last one (the "Z"). */
    private const val FLAG_Z = 0x1F1FF

    /**
     * The ISO 3166-1 alpha-2 code for [text], or `null` when nothing in it names a
     * place. [text] is meant to be the node's display name and its server host joined
     * together — either may carry the country and neither reliably does.
     */
    fun codeFor(text: String): String? {
        if (text.isBlank()) return null
        // A flag emoji first: when a feed bothers to include one it is the author's
        // explicit answer, and nothing else in Unicode uses regional indicators.
        flagEmoji(text)?.let { return it }
        val normalized = normalize(text)
        // Multi-word names, then spelled-out single words, then bare codes. The order is
        // specificity: `united states` must not be decided by `states`, `south korea`
        // must not lose to a bare `korea`, and a host like `mirror.co.uk` must not be
        // decided by whichever two-letter token happens to come first.
        return multiWordMatch(normalized)
            ?: tokenMatch(normalized) { it.length >= MIN_NAME }
            ?: tokenMatch(normalized) { it.length == CODE_LENGTH }
    }

    /**
     * [text] with any flag emoji taken out and the gap it left tidied up.
     *
     * The plate beside a node's name already carries its country, as two ink letters —
     * and a regional-indicator emoji is the one full-colour element a subscription feed
     * can inject into an interface that is otherwise monochrome by construction. Since
     * [codeFor] reads the emoji into the plate, leaving it in the label as well is both
     * a duplicate and the brightest thing on the screen.
     *
     * A display concern only: the stored label, the exported link and the search index
     * all keep the author's original text. Falls back to [text] when the name was
     * nothing but a flag, because an empty row is worse than a coloured one.
     */
    fun plainName(text: String): String {
        val stripped = StringBuilder(text.length)
        var index = 0
        var removed = false
        while (index < text.length) {
            val point = text.codePointAt(index)
            val width = Character.charCount(point)
            if (point in FLAG_A..FLAG_Z) removed = true else stripped.appendCodePoint(point)
            index += width
        }
        if (!removed) return text
        val tidied = stripped.toString()
            .replace(WHITESPACE_RUN, " ")
            .trim()
            .trimStart { it.isWhitespace() || it in LEADING_SEPARATORS }
        return tidied.ifBlank { text }
    }

    /** Any run of spaces the removed emoji may have left doubled up. */
    private val WHITESPACE_RUN = Regex("\\s+")

    /** Separators a feed puts *after* the flag, which become leading once it is gone. */
    private const val LEADING_SEPARATORS = "|-–—·,:"

    private fun multiWordMatch(normalized: String): String? =
        MULTI_WORD.firstOrNull { (alias, _) -> normalized.contains(" $alias ") }?.second

    private inline fun tokenMatch(normalized: String, accept: (String) -> Boolean): String? =
        normalized.split(' ').firstNotNullOfOrNull { token ->
            if (accept(token)) SINGLE_WORD[token] else null
        }

    /** Shortest token treated as a spelled-out name rather than as a code. */
    private const val MIN_NAME = 3

    /** Length of an ISO 3166-1 alpha-2 code. */
    private const val CODE_LENGTH = 2

    /**
     * Decode a flag emoji: two regional-indicator code points in a row are a country
     * code, and nothing else in Unicode uses them. Checked first because when a feed
     * bothers to include one it is the author's explicit answer.
     */
    private fun flagEmoji(text: String): String? {
        var index = 0
        while (index < text.length) {
            val first = text.codePointAt(index)
            val firstWidth = Character.charCount(first)
            if (first in FLAG_A..FLAG_Z && index + firstWidth < text.length) {
                val second = text.codePointAt(index + firstWidth)
                if (second in FLAG_A..FLAG_Z) {
                    val letters = charArrayOf(
                        ('A' + (first - FLAG_A)),
                        ('A' + (second - FLAG_A)),
                    )
                    return String(letters)
                }
            }
            index += firstWidth
        }
        return null
    }

    /**
     * Lower-case, and every run of non-letters collapsed to one space, with a space at
     * each end so a multi-word alias can be matched on whole-word boundaries. Digits
     * go too: `JP2` and `us-east1` should read as `jp` and `us east`.
     */
    private fun normalize(text: String): String {
        val out = StringBuilder(" ")
        text.lowercase().forEach { ch ->
            if (ch.isLetter()) out.append(ch) else if (out.last() != ' ') out.append(' ')
        }
        if (out.last() != ' ') out.append(' ')
        return out.toString()
    }

    /**
     * Aliases that contain a space, matched against the whole normalized string. In
     * specificity order: `united states` has to win over `states`, and `south korea`
     * over a bare `korea`.
     */
    private val MULTI_WORD: List<Pair<String, String>> = listOf(
        "united arab emirates" to "AE",
        "united states" to "US",
        "united kingdom" to "GB",
        "great britain" to "GB",
        "czech republic" to "CZ",
        "south korea" to "KR",
        "north korea" to "KP",
        "south africa" to "ZA",
        "saudi arabia" to "SA",
        "new zealand" to "NZ",
        "hong kong" to "HK",
        "costa rica" to "CR",
        "new york" to "US",
        "los angeles" to "US",
        "san jose" to "US",
        "san francisco" to "US",
        "silicon valley" to "US",
        "las vegas" to "US",
        "salt lake" to "US",
        "sao paulo" to "BR",
        "rio de janeiro" to "BR",
        "buenos aires" to "AR",
        "kuala lumpur" to "MY",
        "ho chi minh" to "VN",
        "tel aviv" to "IL",
        "cape town" to "ZA",
        "st petersburg" to "RU",
        "saint petersburg" to "RU",
        "nizhny novgorod" to "RU",
        "южная корея" to "KR",
        "нью йорк" to "US",
        "сша" to "US",
        "оаэ" to "AE",
    )

    /**
     * Single-token aliases: country names in English and Russian, the cities feeds
     * label nodes with, and — only where the token is not also an ordinary word — the
     * bare ISO code, because `jp1.example.com` is how half of all feeds spell Japan.
     *
     * Codes deliberately absent as bare tokens: `it`, `in`, `no`, `at`, `be`, `is`,
     * `am`, `do`, `me`, `so`, `to`, `id`, `by`, `my`, `co`, `th`, `pe`. Every one of
     * them is a common English word or a frequent host fragment, and a node called
     * "Node to test" must not become Tonga.
     */
    private val SINGLE_WORD: Map<String, String> = buildMap {
        fun of(code: String, vararg names: String) = names.forEach { put(it, code) }

        // ---- Europe ----
        of("GB", "gb", "uk", "britain", "england", "london", "manchester", "лондон", "британия")
        of("DE", "de", "ger", "germany", "deutschland", "frankfurt", "berlin", "munich", "германия")
        of("DE", "nuremberg", "франкфурт", "берлин")
        of("NL", "nl", "netherlands", "holland", "amsterdam", "нидерланды", "амстердам")
        of("FR", "fr", "france", "paris", "marseille", "франция", "париж")
        of("SE", "se", "sweden", "stockholm", "швеция", "стокгольм")
        of("FI", "fi", "finland", "helsinki", "финляндия", "хельсинки")
        of("NO", "nor", "norway", "oslo", "норвегия", "осло")
        of("DK", "dk", "denmark", "copenhagen", "дания")
        of("CH", "ch", "switzerland", "swiss", "zurich", "geneva", "швейцария", "цюрих")
        of("AT", "aut", "austria", "vienna", "австрия", "вена")
        of("IT", "ita", "italy", "milan", "rome", "италия", "милан", "рим")
        of("ES", "es", "spain", "madrid", "barcelona", "испания", "мадрид")
        of("PT", "pt", "portugal", "lisbon", "португалия")
        of("PL", "pl", "poland", "warsaw", "польша", "варшава")
        of("CZ", "cz", "czechia", "czech", "prague", "чехия", "прага")
        of("SK", "sk", "slovakia", "bratislava", "словакия")
        of("HU", "hu", "hungary", "budapest", "венгрия")
        of("RO", "ro", "romania", "bucharest", "румыния")
        of("BG", "bg", "bulgaria", "sofia", "болгария")
        of("GR", "gr", "greece", "athens", "греция")
        of("IE", "ie", "ireland", "dublin", "ирландия")
        of("IS", "isl", "iceland", "reykjavik", "исландия")
        of("LU", "lu", "luxembourg", "люксембург")
        of("BE", "bel", "belgium", "brussels", "бельгия")
        of("HR", "hr", "croatia", "zagreb", "хорватия")
        of("RS", "rs", "serbia", "belgrade", "сербия")
        of("MD", "md", "moldova", "chisinau", "молдова", "молдавия")
        of("LT", "lt", "lithuania", "vilnius", "литва")
        of("LV", "lv", "latvia", "riga", "латвия", "рига")
        of("EE", "ee", "estonia", "tallinn", "эстония", "таллин")

        // ---- Eastern Europe / Caucasus / Central Asia ----
        of("RU", "ru", "rus", "russia", "russian", "moscow", "россия", "москва", "спб")
        of("UA", "ua", "ukraine", "kyiv", "kiev", "украина", "киев")
        of("BY", "blr", "belarus", "minsk", "беларусь", "белоруссия", "минск")
        of("KZ", "kz", "kazakhstan", "almaty", "astana", "казахстан", "алматы")
        of("AM", "arm", "armenia", "yerevan", "армения", "ереван")
        of("GE", "ge", "georgia", "tbilisi", "грузия", "тбилиси")
        of("AZ", "az", "azerbaijan", "baku", "азербайджан", "баку")
        of("TR", "tr", "turkey", "turkiye", "istanbul", "турция", "стамбул")

        // ---- Asia / Pacific ----
        of("JP", "jp", "jpn", "japan", "tokyo", "osaka", "япония", "токио", "осака")
        of("KR", "kr", "kor", "korea", "seoul", "корея", "сеул")
        of("CN", "cn", "china", "shanghai", "beijing", "shenzhen", "китай", "шанхай", "пекин")
        of("HK", "hk", "hongkong", "гонконг")
        of("TW", "tw", "taiwan", "taipei", "тайвань")
        of("SG", "sg", "singapore", "сингапур")
        of("MY", "malaysia", "малайзия")
        of("TH", "thailand", "bangkok", "тайланд", "таиланд")
        of("VN", "vn", "vietnam", "hanoi", "вьетнам")
        of("PH", "ph", "philippines", "manila", "филиппины")
        of("IN", "ind", "india", "mumbai", "delhi", "chennai", "индия")
        of("PK", "pk", "pakistan", "karachi", "пакистан")
        of("AU", "au", "australia", "sydney", "melbourne", "австралия", "сидней")
        of("NZ", "nzl", "auckland")
        of("ID", "idn", "indonesia", "jakarta", "индонезия")

        // ---- Middle East ----
        of("AE", "ae", "uae", "emirates", "dubai", "оаэ", "дубай")
        of("IL", "il", "israel", "израиль")
        of("SA", "sau", "riyadh")
        of("QA", "qa", "qatar", "doha", "катар")
        of("IR", "ir", "iran", "tehran", "иран")

        // ---- Americas ----
        of("US", "us", "usa", "america", "american", "chicago", "dallas", "miami", "seattle")
        of("US", "atlanta", "phoenix", "denver", "ashburn", "америка")
        of("CA", "ca", "canada", "toronto", "montreal", "vancouver", "канада", "торонто")
        of("BR", "br", "brazil", "brasil", "бразилия")
        of("AR", "ar", "argentina", "аргентина")
        of("CL", "cl", "chile", "santiago", "чили")
        of("MX", "mx", "mexico", "мексика")
        of("CO", "colombia", "bogota", "колумбия")
        of("PE", "peru", "lima", "перу")

        // ---- Africa ----
        of("ZA", "za", "johannesburg", "юар")
        of("EG", "eg", "egypt", "cairo", "египет")
        of("NG", "ng", "nigeria", "lagos", "нигерия")
        of("KE", "ke", "kenya", "nairobi", "кения")
        of("MA", "mar", "morocco", "casablanca", "марокко")
    }
}
