package com.fadysaeed.pdfx

import com.fadysaeed.pdfx.engine.Numbers
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cells show as Excel shows them, by the format codes Excel uses (cases from Apache POI's date tests among them). */
class NumbersTest {
    private fun s(v: Double, f: String) = Numbers.show(v, f)

    @Test fun numbers() {
        assertEquals("1,234.50", s(1234.5, "#,##0.00"))
        assertEquals("12.5%", s(0.125, "0.0%"))
        assertEquals("(1,234)", s(-1234.0, "#,##0;(#,##0)"))
        assertEquals("\$1,250.00", s(1250.0, "\$#,##0.00"))
        assertEquals("€ 1,250.00", s(1250.0, "[\$€-2] #,##0.00"))
        assertEquals("0.5", s(0.5, "General"))
        assertEquals("3", s(3.0, "General"))
        assertEquals("0.3333333333", s(1.0 / 3, "General"))
        assertEquals("1.23E+04", s(12345.678, "0.00E+00"))
        assertEquals("-5.5", s(-5.5, "0.0"))
        assertEquals("7 kg", s(7.0, "0\" kg\""))
        assertEquals("1,235K", s(1234567.0, "#,##0,\"K\""))
        assertEquals("042", s(42.0, "000"))
        assertEquals("1,234.57", s(1234.5678, "#,##0.0#"))
        assertEquals("12.0", s(12.0, "#,##0.0#"))
        assertEquals("\$-", s(0.0, "_(\$* #,##0.00_);_(\$* (#,##0.00);_(\$* \"-\"??_);_(@_)"))
    }

    @Test fun datesAndTimes() {
        assertEquals("15/03/2023", s(45000.0, "dd/mm/yyyy"))
        assertEquals("15 March 2023 12:00", s(45000.5, "d mmmm yyyy h:mm"))
        assertEquals("6:00 PM", s(0.75, "h:mm AM/PM"))
        assertEquals("36:00", s(1.5, "[h]:mm"))
        val v = 19278.607951388887
        assertEquals("11-10-52", s(v, "d-m-y"))
        assertEquals("Saturday-October-1952", s(v, "dddd-mmmm-yyyy"))
        assertEquals("02:35:27 p", s(v, "hh:mm:ss a/p"))
        assertEquals("2:35:27.000 P", s(v, "h:m:s.000 A/P"))
        assertEquals("11 days 2 PM", s(v, "d \"days\" h am/pm"))
        val w = 37653.1702084375
        assertEquals("04:05:06.009 AM", s(w, "hh:mm:ss.000 am/pm"))
        assertEquals("4:5:6.01", s(w, "h:m:s.00"))
        assertEquals("Sat-Feb-2003", s(w, "ddd-mmm-yyy"))
    }
}
