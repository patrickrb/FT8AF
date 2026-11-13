package com.k1af.ft8af.html;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link HtmlContext#htmlEscape(String)} — the single central
 * escaper the web-logbook {@code LogHttpServer} uses on every request-derived
 * value before it enters generated markup.
 *
 * <p>These lock the invariant that the five markup-significant characters
 * {@code & < > " '} are all neutralised, closing both the element-context
 * ({@code <script>}) and, critically, the attribute-context ({@code "><script>})
 * XSS that the previous ad-hoc {@code .replace("<", "&lt;")} calls left open
 * (they escaped only {@code <}/{@code >}, so a {@code "} still broke out of a
 * {@code value="…"} attribute).
 */
public class HtmlEscapeTest {

    @Test
    public void nullBecomesEmpty() {
        assertThat(HtmlContext.htmlEscape(null)).isEmpty();
    }

    @Test
    public void emptyStaysEmpty() {
        assertThat(HtmlContext.htmlEscape("")).isEmpty();
    }

    @Test
    public void plainCallsignUnchanged() {
        // legitimate callsigns contain no markup-significant characters
        assertThat(HtmlContext.htmlEscape("KS3CKC/P")).isEqualTo("KS3CKC/P");
    }

    @Test
    public void escapesEachSignificantCharacter() {
        assertThat(HtmlContext.htmlEscape("&")).isEqualTo("&amp;");
        assertThat(HtmlContext.htmlEscape("<")).isEqualTo("&lt;");
        assertThat(HtmlContext.htmlEscape(">")).isEqualTo("&gt;");
        assertThat(HtmlContext.htmlEscape("\"")).isEqualTo("&quot;");
        assertThat(HtmlContext.htmlEscape("'")).isEqualTo("&#39;");
    }

    @Test
    public void ampersandEscapedBeforeOtherEntities() {
        // & must be escaped first so existing entities are not double-decoded;
        // "&lt;" as literal input must round-trip to a visible "&lt;", not "<".
        assertThat(HtmlContext.htmlEscape("&lt;")).isEqualTo("&amp;lt;");
    }

    @Test
    public void elementContextPayloadNeutralised() {
        String escaped = HtmlContext.htmlEscape("<script>alert(1)</script>");
        assertThat(escaped).isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(escaped).doesNotContain("<script>");
    }

    @Test
    public void attributeContextBreakoutNeutralised() {
        // the payload from the finding: ?callsign="><script>… inside value="%s"
        String escaped = HtmlContext.htmlEscape("\"><script>alert(document.cookie)</script>");
        // the closing quote that used to break out of the attribute is gone
        assertThat(escaped).doesNotContain("\"");
        assertThat(escaped).doesNotContain("<");
        assertThat(escaped).doesNotContain(">");
        assertThat(escaped).isEqualTo(
                "&quot;&gt;&lt;script&gt;alert(document.cookie)&lt;/script&gt;");
    }

    @Test
    public void singleQuoteAttributeBreakoutNeutralised() {
        // single-quoted attribute breakout: value='…' with a ' payload
        String escaped = HtmlContext.htmlEscape("' onmouseover='alert(1)");
        assertThat(escaped).doesNotContain("'");
        assertThat(escaped).isEqualTo("&#39; onmouseover=&#39;alert(1)");
    }

    @Test
    public void mixedContentEscapedInPlace() {
        assertThat(HtmlContext.htmlEscape("a & b < c > d \" e ' f"))
                .isEqualTo("a &amp; b &lt; c &gt; d &quot; e &#39; f");
    }

    // ---- URL encoding -------------------------------------------------------
    // HTML escaping alone is wrong inside an href: the browser resolves entities
    // BEFORE parsing the URL, so an escaped &amp; becomes a real & and splits the
    // query string. These lock the percent-encoding that prevents that.

    @Test
    public void queryValue_percentEncodesParameterSeparators() {
        // Parameter pollution: a callsign of A&pageSize=9999 must stay one value.
        String encoded = HtmlContext.urlQueryValue("A&pageSize=9999");
        assertThat(encoded).doesNotContain("&");
        assertThat(encoded).doesNotContain("=");
        assertThat(encoded).isEqualTo("A%26pageSize%3D9999");
    }

    @Test
    public void queryValue_percentEncodesFragmentAndSpace() {
        assertThat(HtmlContext.urlQueryValue("K1 ABC#frag")).isEqualTo("K1+ABC%23frag");
    }

    @Test
    public void queryValue_leavesNoMarkupSignificantCharacters() {
        String encoded = HtmlContext.urlQueryValue("\"><script>alert(1)</script>");
        assertThat(encoded).doesNotContain("\"");
        assertThat(encoded).doesNotContain("<");
        assertThat(encoded).doesNotContain(">");
        assertThat(encoded).doesNotContain("'");
    }

    @Test
    public void queryValue_nullIsEmpty() {
        assertThat(HtmlContext.urlQueryValue(null)).isEmpty();
    }

    @Test
    public void pathSegment_encodesSpaceAsPercent20NotPlus() {
        // In a path segment '+' is a literal plus, so the form encoder's '+' is wrong.
        assertThat(HtmlContext.urlPathSegment("K1 ABC")).isEqualTo("K1%20ABC");
    }

    @Test
    public void pathSegment_cannotEscapeItsOwnSegment() {
        // A '/' must not let the value reach a different route, and '?'/'#' must not
        // start a query or fragment.
        String encoded = HtmlContext.urlPathSegment("../admin?x=1#y");
        assertThat(encoded).doesNotContain("/");
        assertThat(encoded).doesNotContain("?");
        assertThat(encoded).doesNotContain("#");
    }

    @Test
    public void pathSegment_nullIsEmpty() {
        assertThat(HtmlContext.urlPathSegment(null)).isEmpty();
    }

    // ---- tableCellEscaped ---------------------------------------------------

    @Test
    public void tableCellEscaped_escapesEveryValue() {
        // Straight DB columns (mode/RST/date/band/freq) reach the page through this;
        // imported ADIF can carry markup in any of them.
        StringBuilder sb = new StringBuilder();
        HtmlContext.tableCellEscaped(sb, "FT8", "<script>alert(1)</script>");
        assertThat(sb.toString())
                .isEqualTo("<td>FT8</td><td>&lt;script&gt;alert(1)&lt;/script&gt;</td>\n");
    }

    @Test
    public void tableCellEscaped_nullValueBecomesEmptyCell() {
        StringBuilder sb = new StringBuilder();
        HtmlContext.tableCellEscaped(sb, (String) null);
        assertThat(sb.toString()).isEqualTo("<td></td>\n");
    }
}
