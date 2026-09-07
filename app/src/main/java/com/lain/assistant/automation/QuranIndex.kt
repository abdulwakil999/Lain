package com.lain.assistant.automation

import com.lain.assistant.agent.FuzzyMatch

/**
 * The 114 surahs, held in the app.
 *
 * Small enough to ship — a few kilobytes — and shipping it is the point: working
 * out that "play Al-Kahf" means surah 18 should not need a network round trip, and
 * a recitation you asked for on a bad connection should still start.
 *
 * Names are matched through [FuzzyMatch] like every other name in the app, so
 * "al kahf", "Al-Kahf", "kahf" and "the cave" all reach the same place.
 */
object QuranIndex {

    data class Surah(
        val number: Int,
        val arabicName: String,
        val name: String,
        val meaning: String,
        val ayahCount: Int
    ) {
        fun describe(): String = "$number. $name ($arabicName) — $meaning, $ayahCount ayat"
    }

    val all: List<Surah> = listOf(
        Surah(1, "الفاتحة", "Al-Fatihah", "The Opening", 7),
        Surah(2, "البقرة", "Al-Baqarah", "The Cow", 286),
        Surah(3, "آل عمران", "Aal-Imran", "The Family of Imran", 200),
        Surah(4, "النساء", "An-Nisa", "The Women", 176),
        Surah(5, "المائدة", "Al-Ma'idah", "The Table Spread", 120),
        Surah(6, "الأنعام", "Al-An'am", "The Cattle", 165),
        Surah(7, "الأعراف", "Al-A'raf", "The Heights", 206),
        Surah(8, "الأنفال", "Al-Anfal", "The Spoils of War", 75),
        Surah(9, "التوبة", "At-Tawbah", "The Repentance", 129),
        Surah(10, "يونس", "Yunus", "Jonah", 109),
        Surah(11, "هود", "Hud", "Hud", 123),
        Surah(12, "يوسف", "Yusuf", "Joseph", 111),
        Surah(13, "الرعد", "Ar-Ra'd", "The Thunder", 43),
        Surah(14, "ابراهيم", "Ibrahim", "Abraham", 52),
        Surah(15, "الحجر", "Al-Hijr", "The Rocky Tract", 99),
        Surah(16, "النحل", "An-Nahl", "The Bee", 128),
        Surah(17, "الإسراء", "Al-Isra", "The Night Journey", 111),
        Surah(18, "الكهف", "Al-Kahf", "The Cave", 110),
        Surah(19, "مريم", "Maryam", "Mary", 98),
        Surah(20, "طه", "Ta-Ha", "Ta-Ha", 135),
        Surah(21, "الأنبياء", "Al-Anbiya", "The Prophets", 112),
        Surah(22, "الحج", "Al-Hajj", "The Pilgrimage", 78),
        Surah(23, "المؤمنون", "Al-Mu'minun", "The Believers", 118),
        Surah(24, "النور", "An-Nur", "The Light", 64),
        Surah(25, "الفرقان", "Al-Furqan", "The Criterion", 77),
        Surah(26, "الشعراء", "Ash-Shu'ara", "The Poets", 227),
        Surah(27, "النمل", "An-Naml", "The Ant", 93),
        Surah(28, "القصص", "Al-Qasas", "The Stories", 88),
        Surah(29, "العنكبوت", "Al-Ankabut", "The Spider", 69),
        Surah(30, "الروم", "Ar-Rum", "The Romans", 60),
        Surah(31, "لقمان", "Luqman", "Luqman", 34),
        Surah(32, "السجدة", "As-Sajdah", "The Prostration", 30),
        Surah(33, "الأحزاب", "Al-Ahzab", "The Combined Forces", 73),
        Surah(34, "سبإ", "Saba", "Sheba", 54),
        Surah(35, "فاطر", "Fatir", "Originator", 45),
        Surah(36, "يس", "Ya-Sin", "Ya Sin", 83),
        Surah(37, "الصافات", "As-Saffat", "Those who set the Ranks", 182),
        Surah(38, "ص", "Sad", "The Letter Sad", 88),
        Surah(39, "الزمر", "Az-Zumar", "The Troops", 75),
        Surah(40, "غافر", "Ghafir", "The Forgiver", 85),
        Surah(41, "فصلت", "Fussilat", "Explained in Detail", 54),
        Surah(42, "الشورى", "Ash-Shuraa", "The Consultation", 53),
        Surah(43, "الزخرف", "Az-Zukhruf", "The Ornaments of Gold", 89),
        Surah(44, "الدخان", "Ad-Dukhan", "The Smoke", 59),
        Surah(45, "الجاثية", "Al-Jathiyah", "The Crouching", 37),
        Surah(46, "الأحقاف", "Al-Ahqaf", "The Wind-Curved Sandhills", 35),
        Surah(47, "محمد", "Muhammad", "Muhammad", 38),
        Surah(48, "الفتح", "Al-Fath", "The Victory", 29),
        Surah(49, "الحجرات", "Al-Hujurat", "The Rooms", 18),
        Surah(50, "ق", "Qaf", "The Letter Qaf", 45),
        Surah(51, "الذاريات", "Adh-Dhariyat", "The Winnowing Winds", 60),
        Surah(52, "الطور", "At-Tur", "The Mount", 49),
        Surah(53, "النجم", "An-Najm", "The Star", 62),
        Surah(54, "القمر", "Al-Qamar", "The Moon", 55),
        Surah(55, "الرحمن", "Ar-Rahman", "The Beneficent", 78),
        Surah(56, "الواقعة", "Al-Waqi'ah", "The Inevitable", 96),
        Surah(57, "الحديد", "Al-Hadid", "The Iron", 29),
        Surah(58, "المجادلة", "Al-Mujadila", "The Pleading Woman", 22),
        Surah(59, "الحشر", "Al-Hashr", "The Exile", 24),
        Surah(60, "الممتحنة", "Al-Mumtahanah", "She that is to be examined", 13),
        Surah(61, "الصف", "As-Saf", "The Ranks", 14),
        Surah(62, "الجمعة", "Al-Jumu'ah", "The Congregation", 11),
        Surah(63, "المنافقون", "Al-Munafiqun", "The Hypocrites", 11),
        Surah(64, "التغابن", "At-Taghabun", "The Mutual Disillusion", 18),
        Surah(65, "الطلاق", "At-Talaq", "The Divorce", 12),
        Surah(66, "التحريم", "At-Tahrim", "The Prohibition", 12),
        Surah(67, "الملك", "Al-Mulk", "The Sovereignty", 30),
        Surah(68, "القلم", "Al-Qalam", "The Pen", 52),
        Surah(69, "الحاقة", "Al-Haqqah", "The Reality", 52),
        Surah(70, "المعارج", "Al-Ma'arij", "The Ascending Stairways", 44),
        Surah(71, "نوح", "Nuh", "Noah", 28),
        Surah(72, "الجن", "Al-Jinn", "The Jinn", 28),
        Surah(73, "المزمل", "Al-Muzzammil", "The Enshrouded One", 20),
        Surah(74, "المدثر", "Al-Muddaththir", "The Cloaked One", 56),
        Surah(75, "القيامة", "Al-Qiyamah", "The Resurrection", 40),
        Surah(76, "الانسان", "Al-Insan", "The Man", 31),
        Surah(77, "المرسلات", "Al-Mursalat", "The Emissaries", 50),
        Surah(78, "النبإ", "An-Naba", "The Tidings", 40),
        Surah(79, "النازعات", "An-Nazi'at", "Those who drag forth", 46),
        Surah(80, "عبس", "Abasa", "He Frowned", 42),
        Surah(81, "التكوير", "At-Takwir", "The Overthrowing", 29),
        Surah(82, "الإنفطار", "Al-Infitar", "The Cleaving", 19),
        Surah(83, "المطففين", "Al-Mutaffifin", "The Defrauding", 36),
        Surah(84, "الإنشقاق", "Al-Inshiqaq", "The Sundering", 25),
        Surah(85, "البروج", "Al-Buruj", "The Mansions of the Stars", 22),
        Surah(86, "الطارق", "At-Tariq", "The Nightcomer", 17),
        Surah(87, "الأعلى", "Al-A'la", "The Most High", 19),
        Surah(88, "الغاشية", "Al-Ghashiyah", "The Overwhelming", 26),
        Surah(89, "الفجر", "Al-Fajr", "The Dawn", 30),
        Surah(90, "البلد", "Al-Balad", "The City", 20),
        Surah(91, "الشمس", "Ash-Shams", "The Sun", 15),
        Surah(92, "الليل", "Al-Layl", "The Night", 21),
        Surah(93, "الضحى", "Ad-Duhaa", "The Morning Hours", 11),
        Surah(94, "الشرح", "Ash-Sharh", "The Relief", 8),
        Surah(95, "التين", "At-Tin", "The Fig", 8),
        Surah(96, "العلق", "Al-Alaq", "The Clot", 19),
        Surah(97, "القدر", "Al-Qadr", "The Power", 5),
        Surah(98, "البينة", "Al-Bayyinah", "The Clear Proof", 8),
        Surah(99, "الزلزلة", "Az-Zalzalah", "The Earthquake", 8),
        Surah(100, "العاديات", "Al-Adiyat", "The Courser", 11),
        Surah(101, "القارعة", "Al-Qari'ah", "The Calamity", 11),
        Surah(102, "التكاثر", "At-Takathur", "The Rivalry in world increase", 8),
        Surah(103, "العصر", "Al-Asr", "The Declining Day", 3),
        Surah(104, "الهمزة", "Al-Humazah", "The Traducer", 9),
        Surah(105, "الفيل", "Al-Fil", "The Elephant", 5),
        Surah(106, "قريش", "Quraysh", "Quraysh", 4),
        Surah(107, "الماعون", "Al-Ma'un", "The Small Kindnesses", 7),
        Surah(108, "الكوثر", "Al-Kawthar", "The Abundance", 3),
        Surah(109, "الكافرون", "Al-Kafirun", "The Disbelievers", 6),
        Surah(110, "النصر", "An-Nasr", "The Divine Support", 3),
        Surah(111, "المسد", "Al-Masad", "The Palm Fibre", 5),
        Surah(112, "الإخلاص", "Al-Ikhlas", "The Sincerity", 4),
        Surah(113, "الفلق", "Al-Falaq", "The Daybreak", 5),
        Surah(114, "الناس", "An-Nas", "Mankind", 6),
    )

    fun byNumber(number: Int): Surah? = all.firstOrNull { it.number == number }

    /**
     * Finds a surah from what the user said.
     *
     * Tries the number, then the transliterated name, then the English meaning —
     * people ask for "the cave" as readily as for "Al-Kahf". An ambiguous match
     * returns null rather than a guess: starting the wrong recitation is a small
     * insult, and an avoidable one.
     */
    fun find(spoken: String): Surah? {
        val query = spoken.trim().lowercase()
            .removePrefix("surah ").removePrefix("surat ").removePrefix("sura ")
            .removePrefix("chapter ").trim()
        if (query.isEmpty()) return null

        query.toIntOrNull()?.let { return byNumber(it) }

        val byName = FuzzyMatch.best(query, all) { it.name }
        if (byName is FuzzyMatch.Result.Found && byName.hit.score >= FuzzyMatch.CERTAIN) {
            return byName.hit.value
        }

        val byMeaning = FuzzyMatch.best(query, all) { it.meaning }
        if (byMeaning is FuzzyMatch.Result.Found && byMeaning.hit.score >= FuzzyMatch.CERTAIN) {
            return byMeaning.hit.value
        }

        // Below certain, the name match is still the better guess of the two.
        return (byName as? FuzzyMatch.Result.Found)?.hit?.value
    }

    /**
     * The same lookup, but only when it is sure.
     *
     * [find] ends on its best guess, which is right once the user has said "surah"
     * — they mean one of the 114 and the only question is which. It is wrong when
     * the sole evidence is the verb: "read the screen" came back as a surah with a
     * weak name score, and Lain started a recitation instead of reading the display.
     * Callers with no other evidence use this one.
     */
    fun findCertain(spoken: String): Surah? {
        val query = spoken.trim().lowercase()
            .removePrefix("surah ").removePrefix("surat ").removePrefix("sura ")
            .removePrefix("chapter ").trim()
        if (query.isEmpty()) return null
        query.toIntOrNull()?.let { return byNumber(it) }

        val byName = FuzzyMatch.best(query, all) { it.name }
        if (byName is FuzzyMatch.Result.Found && byName.hit.score >= FuzzyMatch.CERTAIN) {
            return byName.hit.value
        }
        val byMeaning = FuzzyMatch.best(query, all) { it.meaning }
        if (byMeaning is FuzzyMatch.Result.Found && byMeaning.hit.score >= FuzzyMatch.CERTAIN) {
            return byMeaning.hit.value
        }
        return null
    }
}
