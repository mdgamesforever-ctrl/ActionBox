package com.futurepath.actionbox.classification

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Arabic (Modern Standard + Jordanian/Levantine colloquial) parity check for
 * [NotificationClassifier], mirroring [NotificationClassifierAccuracyTest]'s exact structure and
 * size (10 hand-authored cases per category) so the two reports are directly comparable. Every
 * verb/phrase form used below is one actually present in NotificationClassifier's ARABIC_*
 * pattern lists — this is deliberately NOT a corpus of arbitrary "should obviously work" Arabic
 * sentences, since (like the English coverage it mirrors) this is a curated v1 heuristic engine,
 * not an NLP model that generalizes to unseen phrasing.
 */
class ArabicClassificationAccuracyTest {

    data class Case(
        val sourceApp: String,
        val sender: String,
        val text: String,
        val expected: ClassifiedState
    )

    private val cases = listOf(
        // ---------------- ACTION (10) ----------------
        Case("com.whatsapp", "سارة", "ابعتيلي الملف لو سمحت", ClassifiedState.ACTION),
        Case("com.whatsapp", "أحمد", "ممكن تجيبلي القهوة الحين", ClassifiedState.ACTION),
        Case("com.whatsapp", "المدير", "من فضلك راجعي العقد وأكدي الاستلام", ClassifiedState.ACTION),
        Case("com.whatsapp", "أمي", "جيبلي الحليب وانت راجع لو سمحت", ClassifiedState.ACTION),
        Case("com.whatsapp", "الموارد البشرية", "محتاج تدفعي الفاتورة قبل الجمعة", ClassifiedState.ACTION),
        Case("com.whatsapp", "خالد", "ياريت تدفعي الفاتورة اليوم", ClassifiedState.ACTION),
        Case("com.whatsapp", "ياسمين", "بدي اتصلي فيني لما توصلي", ClassifiedState.ACTION),
        Case("com.whatsapp", "المحاسب", "ممكن تسددي المبلغ وتأكدي معي", ClassifiedState.ACTION),
        Case("com.whatsapp", "رنا", "احضريلي الأوراق لو سمحتي", ClassifiedState.ACTION),
        Case("com.whatsapp", "وائل", "بدك تلغي الحجز أو تأكديه؟", ClassifiedState.ACTION),

        // ---------------- REPLY (10) ----------------
        Case("com.whatsapp", "لمى", "شو رأيك بالخطة؟", ClassifiedState.REPLY),
        Case("com.whatsapp", "عمر", "خبرني لما توصل", ClassifiedState.REPLY),
        Case("com.whatsapp", "هبة", "قلي شو صار معك", ClassifiedState.REPLY),
        Case("com.whatsapp", "زيد", "رد علي بأقرب وقت", ClassifiedState.REPLY),
        Case("com.whatsapp", "دانا", "شفت الرسالة؟", ClassifiedState.REPLY),
        Case("com.whatsapp", "فراس", "في حدا فيه؟", ClassifiedState.REPLY),
        Case("com.whatsapp", "نور", "انت موجود؟", ClassifiedState.REPLY),
        Case("com.whatsapp", "ريم", "خبرني إذا الخطة نفس الاتفاق", ClassifiedState.REPLY),
        Case("com.whatsapp", "طارق", "شو الوضع معك؟", ClassifiedState.REPLY),
        Case("com.whatsapp", "سلمى", "ماشي الحال؟", ClassifiedState.REPLY),

        // ---------------- WAITING (10) ----------------
        Case("com.whatsapp", "أحمد", "لسا عم اشتغل عليها", ClassifiedState.WAITING),
        Case("com.whatsapp", "سارة", "راح ارجعلك بعد شوي", ClassifiedState.WAITING),
        Case("com.whatsapp", "الدعم الفني", "طلبك جاري المراجعة حالياً", ClassifiedState.WAITING),
        Case("com.whatsapp", "خالد", "الطلب قيد الانتظار من الفريق", ClassifiedState.WAITING),
        Case("com.whatsapp", "المكتب", "المعاملة قيد المعالجة الآن", ClassifiedState.WAITING),
        Case("com.whatsapp", "ياسمين", "هرجعلك خلال شوي", ClassifiedState.WAITING),
        Case("com.whatsapp", "المحاسب", "خليني اتأكد وارجعلك", ClassifiedState.WAITING),
        Case("com.whatsapp", "رنا", "لحظة بس وبكون جاهز", ClassifiedState.WAITING),
        Case("com.whatsapp", "وائل", "بعطيك خبر أول ما يوصلني رد", ClassifiedState.WAITING),
        Case("com.whatsapp", "الدعم الفني", "تم الاستلام وجاري المراجعة", ClassifiedState.WAITING),

        // ---------------- DEADLINE (10) ----------------
        Case("com.whatsapp", "المؤجر", "لازم دفع الإيجار قبل الجمعة", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "الجامعة", "آخر موعد لتسليم الطلب هو الخميس", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "الدائرة", "الموعد النهائي للتجديد قبل السبت", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "المتجر", "العرض ينتهي حتى الأحد", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "شركة الكهرباء", "مهلة السداد حتى نهاية الشهر", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "الجامعة", "اخر يوم للتسجيل هو الاثنين", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "البنك", "تاريخ الانتهاء للعرض قبل الثلاثاء", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "المشرف", "لازم تسليم التقرير قبل بكرا", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "الدائرة", "صلاحية العرض تنتهي قبل الأربعاء", ClassifiedState.DEADLINE),
        Case("com.whatsapp", "المكتب", "الاشتراك ينتهي قبل نهاية الاسبوع", ClassifiedState.DEADLINE),

        // ---------------- FYI (10) ----------------
        Case("com.whatsapp", "المتجر", "تم الشحن اليوم", ClassifiedState.FYI),
        Case("com.whatsapp", "شركة الشحن", "تم التوصيل بنجاح", ClassifiedState.FYI),
        Case("com.whatsapp", "المتجر", "تم استلام طلبك", ClassifiedState.FYI),
        Case("com.whatsapp", "البنك", "تم الدفع بنجاح", ClassifiedState.FYI),
        Case("com.whatsapp", "البنك", "رمز التحقق الخاص فيك هو 4821", ClassifiedState.FYI),
        Case("com.whatsapp", "الصالون", "تم تأكيد الحجز", ClassifiedState.FYI),
        Case("com.whatsapp", "شركة الشحن", "تم تسليم الطلب", ClassifiedState.FYI),
        Case("com.whatsapp", "المتجر", "وصلت الطلبية قبل شوي", ClassifiedState.FYI),
        Case("com.whatsapp", "البنك", "تم تنفيذ العملية بنجاح", ClassifiedState.FYI),
        Case("com.whatsapp", "المتجر", "رمز التأكيد هو 9042", ClassifiedState.FYI),

        // ---------------- NOISE (10) ----------------
        Case("com.instagram.android", "انستقرام", "خصم على جميع المنتجات، عرض لفترة محدودة", ClassifiedState.NOISE),
        Case("com.twitter.android", "المتجر", "كوبون مجاني لكل الزبائن اليوم فقط", ClassifiedState.NOISE),
        Case("com.instagram.android", "المتجر", "تخفيضات كبيرة، احجز الان مكانك", ClassifiedState.NOISE),
        Case("com.twitter.android", "المتجر", "عرض حصري لفترة محدودة، لا تفوت الفرصة", ClassifiedState.NOISE),
        Case("com.zhiliaoapp.musically", "المتجر", "مبروك ربحت جائزة اليوم", ClassifiedState.NOISE),
        Case("com.instagram.android", "المتجر", "فزت بجائزة، اضغط هنا للاستلام", ClassifiedState.NOISE),
        Case("com.twitter.android", "المتجر", "خصم كبير وعرض خاص لعملائنا", ClassifiedState.NOISE),
        Case("com.instagram.android", "المتجر", "عرض مجاني لفترة محدودة على التطبيق", ClassifiedState.NOISE),
        Case("com.twitter.android", "المتجر", "كوبون خصم لكل عملية شراء اليوم", ClassifiedState.NOISE),
        Case("com.instagram.android", "المتجر", "تخفيضات وعرض خاص، احجز الان", ClassifiedState.NOISE)
    )

    @Test
    fun runArabicAccuracyReport() {
        val sb = StringBuilder()
        var correct = 0
        val misses = mutableListOf<String>()
        val byCategory = linkedMapOf<ClassifiedState, MutableList<Boolean>>()

        for (case in cases) {
            val normalized = TextNormalizer.normalize(case.text)
            val result = NotificationClassifier.classify(case.sourceApp, case.sender, normalized)
            val isCorrect = result.state == case.expected
            if (isCorrect) correct++
            byCategory.getOrPut(case.expected) { mutableListOf() }.add(isCorrect)
            if (!isCorrect) {
                misses += "  MISS [expected ${case.expected}]: \"${case.text}\" -> got ${result.state} " +
                    "(confidence=${result.confidence}%, summary=${result.summary})"
            }
        }

        val accuracy = 100.0 * correct / cases.size
        sb.appendLine("=== ActionBox NotificationClassifier Arabic accuracy report ===")
        sb.appendLine("Total cases: ${cases.size}")
        sb.appendLine("Correct: $correct")
        sb.appendLine("Accuracy: ${"%.1f".format(accuracy)}%")
        sb.appendLine()
        sb.appendLine("-- By category --")
        for ((category, outcomes) in byCategory) {
            val catCorrect = outcomes.count { it }
            sb.appendLine("$category: $catCorrect/${outcomes.size}")
        }
        sb.appendLine()
        sb.appendLine("-- Failures --")
        if (misses.isEmpty()) sb.appendLine("  (none)") else misses.forEach { sb.appendLine(it) }

        val report = sb.toString()
        println(report)
        File("build/classifier-arabic-accuracy-report.txt").apply {
            parentFile?.mkdirs()
            writeText(report)
        }

        // A real gate (unlike the English NotificationClassifierAccuracyTest/
        // NotificationClassifierCorpusTest reports, which only ever write a file) — this is the
        // one place Task A's "confirm accuracy is comparable to English test cases" requirement
        // is actually enforced by a failing test, not just left for a human to read.
        assertTrue("Arabic corpus accuracy $accuracy% fell below the 90% parity threshold:\n$report", accuracy >= 90.0)
    }

    // ---- Mixed Arabic/English notifications (real-world texting rarely stays in one language) ----

    private fun classify(sourceApp: String, sender: String, text: String) =
        NotificationClassifier.classify(sourceApp, sender, TextNormalizer.normalize(text))

    @Test
    fun `mixed Arabic-English request still reads as ACTION`() {
        val result = classify("com.whatsapp", "سارة", "ارسلي ال PDF")
        assertTrue(
            "expected ACTION, got ${result.state} (confidence=${result.confidence}%)",
            result.state == ClassifiedState.ACTION
        )
    }

    @Test
    fun `English politeness marker plus Arabic verb still reads as ACTION`() {
        val result = classify("com.whatsapp", "خالد", "please ابعتيلي الملف")
        assertTrue(
            "expected ACTION, got ${result.state} (confidence=${result.confidence}%)",
            result.state == ClassifiedState.ACTION
        )
    }

    @Test
    fun `Arabic deadline word plus English day name still reads as DEADLINE`() {
        val result = classify("com.whatsapp", "المكتب", "لازم تسليم التقرير قبل Friday")
        assertTrue(
            "expected DEADLINE, got ${result.state} (confidence=${result.confidence}%)",
            result.state == ClassifiedState.DEADLINE
        )
    }
}
