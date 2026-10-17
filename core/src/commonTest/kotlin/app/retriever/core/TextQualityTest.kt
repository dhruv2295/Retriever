package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextQualityTest {
    // eval/corpus/docs/Document_04.pdf (Chrome export, Type 3 Kohinoor Devanagari) read back by PDFKit on the
    // iOS 27 simulator: missing glyphs come out as NUL and vowel signs land after line breaks.
    private val pdfKit = "सेवा म\u0000,\nप्रधानाचाय\u0000,\nसरस्व\nत\nी \u0000व\u0000ा मं\u0000दर, लखनऊ\n\u0000वषय: क\u0000ा छह म\u0000 प्रवेश के \u0000लए प्राथ\u0000ना पत्र\nमहोदय,\nम\u0000 अपने पुत्र आरव कु मार के \u0000लए आपके \u0000व\u0000ालय क\u0000 क\u0000ा छह म\u0000 प्रवेश चाहत\nा \u0000ँ। उसने इस वष\u0000\nक\u0000ा पाँच क\u0000 परी\u0000ा अ\u0000े अंक\u0000 से उ\u0000ीण\u0000 क\u0000 ह\u0000। उसके सभी प्रमाणपत्र और \u0000ानांतरण पत्र इस\nआवेदन के साथ\nसंलग्न ह\u0000।\nकृ पया उसे अपने \u0000व\u0000ालय म\u0000 प्रवेश द\u0000ने क\u0000 कृ पा कर\u0000। म\u0000 आपका आभारी र\u0000ँगा।\nभवदीय,\nसुर\u0000श कु मार"

    // The same page through poppler's pdftotext on the Mac: every glyph present, conjuncts split at the vowel sign.
    private val pdfToText = """
        मैं अपने पुत्र आरव कु मार के लिए आपके विद्यालय की कक्षा छह में प्रवेश चाहता हूँ। उसने इस वर्ष
        कक्षा पाँच की परीक्षा अच्छे अंकों से उत्तीर्ण की है।
        कृ पया उसे अपने विद्यालय में प्रवेश देने की कृ पा करें। मैं आपका आभारी रहूँगा।
    """.trimIndent()

    @Test
    fun brokenTextLayers() {
        assertTrue(looksBroken(pdfKit))
        assertTrue(looksBroken(pdfToText))
        assertTrue(looksBroken("आरव कु मार"))
        assertTrue(looksBroken("সরস্ব\nী text"))
        assertTrue(looksBroken("ั สวัสดี"))
        assertTrue(looksBroken("Invoice ��� total"))
        assertTrue(looksBroken(" "))
    }

    @Test
    fun cleanText() {
        assertFalse(looksBroken("Rent for the flat in Pune is due on the fifth, with the security deposit paid in March."))
        assertFalse(looksBroken("मैं अपने पुत्र आरव कुमार के लिए आपके विद्यालय की कक्षा छह में प्रवेश चाहता हूँ।"))
        assertFalse(looksBroken("कृपया उसे अपने विद्यालय में प्रवेश देने की कृपा करें। मैं आपका आभारी रहूँगा।"))
        assertFalse(looksBroken("तू कहाँ है? इसे मत छू, यह गरम है। मुझे उसकी बातें याद हैं।"))
        assertFalse(looksBroken("A long English page with one stray replacement � character in it, which is not enough to OCR the page again."))
        assertFalse(looksBroken(""))
        assertFalse(looksBroken("আমি বাংলায় গান গাই। আমার সোনার বাংলা, আমি তোমায় ভালোবাসি।"))
        assertFalse(looksBroken("தமிழ் மொழி மிகவும் பழமையான மொழி. நான் உங்களை சந்தித்ததில் மகிழ்ச்சி."))
        assertFalse(looksBroken("สวัสดีครับ ผมอยากจองโต๊ะสำหรับสี่คนที่ร้านอาหารริมน้ำ วันศุกร์นี้"))
        assertFalse(looksBroken("مَرْحَبًا بِكُمْ في مدينتنا الجميلة، وشكرًا لزيارتكم هذا الأسبوع."))
        assertFalse(looksBroken("Heart ❤️ and café é are fine."))
    }

    @Test
    fun pageTextAfterOcr() {
        val ocr = "मैं अपने पुत्र आरव कुमार के लिए आपके विद्यालय की कक्षा छह में प्रवेश चाहता हूँ।"
        assertEquals(ocr, betterPageText(pdfKit, ocr))
        assertEquals(pdfKit, betterPageText(pdfKit, ""))
        assertEquals("a clean text layer that is longer than OCR", betterPageText("a clean text layer that is longer than OCR", "short"))
        assertEquals("from OCR", betterPageText("", "from OCR"))
    }
}
