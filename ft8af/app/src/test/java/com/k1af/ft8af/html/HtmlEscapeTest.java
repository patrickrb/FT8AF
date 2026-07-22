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
}
