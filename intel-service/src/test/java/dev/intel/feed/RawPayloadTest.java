package dev.intel.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The raw payload of an item is its text exactly as received, including entities, CDATA and attribute quirks. */
class RawPayloadTest {

    private final FeedDocumentReader reader = new FeedDocumentReader();

    private static String rss(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\" "
                + "xmlns:content=\"http://purl.org/rss/1.0/modules/content/\"><channel><title>t</title>"
                + body + "</channel></rss>";
    }

    private List<String> raws(String xml) throws Exception {
        return reader.read(xml.getBytes(StandardCharsets.UTF_8)).stream().map(FeedEntry::rawXml).toList();
    }

    @Test
    void everyRealItemIsKeptByteForByteAsReceived() throws Exception {
        String original = new String(Fixtures.feedBytes(), StandardCharsets.UTF_8);
        List<String> expected = new ArrayList<>();
        Matcher m = Pattern.compile("(?s)<item>.*?</item>").matcher(original);
        while (m.find()) {
            expected.add(m.group());
        }

        List<String> actual = reader.read(Fixtures.feedBytes()).stream().map(FeedEntry::rawXml).toList();

        assertThat(expected).hasSize(8);
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.get(0)).contains("&apos;").contains("<![CDATA[").doesNotContain(" xmlns");
    }

    @Test
    void entitiesAreNotDecodedInTheRawText() throws Exception {
        String item = "<item><title>Tom &amp; Jerry&apos;s &lt;b&gt;</title></item>";

        assertThat(raws(rss(item))).containsExactly(item);
    }

    @Test
    void cdataContainingItemMarkupDoesNotEndTheItemEarly() throws Exception {
        String item = "<item><title>t</title><content:encoded><![CDATA[ </item><item><title>fake</title> ]]>"
                + "</content:encoded></item>";

        List<FeedEntry> items = reader.read(rss(item).getBytes(StandardCharsets.UTF_8));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).rawXml()).isEqualTo(item);
        assertThat(items.get(0).item().title()).isEqualTo("t");
    }

    @Test
    void commentsAndProcessingInstructionsAreSkippedByTheTokenizer() throws Exception {
        String first = "<item><title>one</title><!-- <item><title>ghost</title></item> --></item>";
        String second = "<item><?pi </item> ?><title>two</title></item>";

        assertThat(raws(rss("<!-- before <item> -->" + first + second))).containsExactly(first, second);
    }

    @Test
    void aGreaterThanSignInsideAnAttributeValueIsHandled() throws Exception {
        // '>' is legal inside an attribute value ('<' is not), so the tag must not end at the first '>'.
        String item = "<item note=\"a>b\" other='x>y/>z'><title>t</title><guid isPermaLink=\"false\">g</guid></item>";

        assertThat(raws(rss(item))).containsExactly(item);
    }

    @Test
    void aSelfClosingItemIsASpanOfItsOwn() throws Exception {
        String empty = "<item/>";
        String full = "<item><title>after</title></item>";

        assertThat(raws(rss(empty + full))).containsExactly(empty, full);
    }

    @Test
    void anElementNamedItemInsideAnItemIsNotAnItem() throws Exception {
        String item = "<item><title>outer</title><extra><item>nested</item></extra></item>";

        assertThat(raws(rss(item))).containsExactly(item);
    }

    @Test
    void itemsKeepTheirOwnTextWhenWhitespaceAndOtherElementsSurroundThem() throws Exception {
        String a = "<item><title>a</title></item>";
        String b = "<item><title>b</title></item>";

        assertThat(raws(rss("\n  <link>x</link>\n  " + a + "\n\n  <image><url>u</url></image>\n  " + b + "\n")))
                .containsExactly(a, b);
    }

    @Test
    void aByteOrderMarkDoesNotShiftTheSlices() throws Exception {
        String item = "<item><title>bom</title></item>";
        byte[] body = rss(item).getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        assertThat(reader.read(withBom)).extracting(FeedEntry::rawXml).containsExactly(item);
    }

    @Test
    void aUtf16DocumentIsDecodedBeforeSlicing() throws Exception {
        String item = "<item><title>héllo wörld</title></item>";
        String doc = rss(item).replace("encoding=\"UTF-8\"", "encoding=\"UTF-16\"");

        assertThat(reader.read(doc.getBytes(StandardCharsets.UTF_16))).extracting(FeedEntry::rawXml)
                .containsExactly(item);
    }

    @Test
    void aDeclaredLegacyEncodingIsHonoured() throws Exception {
        String item = "<item><title>café</title></item>";
        String doc = rss(item).replace("encoding=\"UTF-8\"", "encoding=\"ISO-8859-1\"");

        assertThat(reader.read(doc.getBytes(StandardCharsets.ISO_8859_1))).extracting(FeedEntry::rawXml)
                .containsExactly(item);
    }

    @Test
    void anItemDeclaringItsOwnDefaultNamespaceKeepsItsExactText() throws Exception {
        String plain = "<item><title>plain</title><guid>g1</guid></item>";
        String foreign = "<item xmlns=\"http://other.example/ns\"><title>foreign</title></item>";

        List<FeedEntry> items = reader.read(rss(plain + foreign).getBytes(StandardCharsets.UTF_8));

        assertThat(items).extracting(FeedEntry::rawXml).containsExactly(plain, foreign);
        assertThat(items).extracting(e -> e.item().title()).containsExactly("plain", "foreign");
    }

    @Test
    void everyItemIsBoundEvenWhenOtherElementsSitBetweenThem() throws Exception {
        String a = "<item><title>a</title></item>";
        String b = "<item><title>b</title></item>";
        String c = "<item><title>c</title></item>";

        List<FeedEntry> items = reader.read(rss("<image><url>u</url></image>" + a
                + "<atom:link xmlns:atom=\"http://www.w3.org/2005/Atom\" href=\"x\"/>" + b + "<ttl>60</ttl>" + c
                + "<image><url>again</url></image>").getBytes(StandardCharsets.UTF_8));

        assertThat(items).extracting(e -> e.item().title()).containsExactly("a", "b", "c");
        assertThat(items).extracting(FeedEntry::rawXml).containsExactly(a, b, c);
    }
}
