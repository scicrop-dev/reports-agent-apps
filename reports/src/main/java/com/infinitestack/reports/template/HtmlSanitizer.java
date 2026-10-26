package com.infinitestack.reports.template;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * Makes the rendered report safe to show and to print (MASTER section 19, ARCHITECTURE 16-17).
 * The report is presentation only: no scripts, no event handlers, no frames, no forms and no
 * resource loaded from anywhere. Images must arrive inside the data, as {@code data:} URIs.
 */
@Component
public class HtmlSanitizer {

    /** url(...) whose target is not a data: URI. */
    private static final Pattern CSS_URL = Pattern.compile("url\\s*\\(\\s*(['\"]?)(?!data:)[^)]*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_IMPORT = Pattern.compile("@import[^;]*;?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_DANGEROUS = Pattern.compile("expression\\s*\\(|javascript:|behavior\\s*:|-moz-binding", Pattern.CASE_INSENSITIVE);

    private final Safelist safelist = Safelist.relaxed()
            .addTags("hr", "section", "header", "footer", "article", "main", "aside", "figure", "figcaption",
                    "small", "mark", "time", "caption")
            .addAttributes(":all", "class", "id", "style", "title")
            .addAttributes("td", "rowspan", "colspan")
            .addAttributes("th", "rowspan", "colspan", "scope")
            .removeProtocols("img", "src", "http", "https")
            .addProtocols("img", "src", "data")
            .removeProtocols("a", "href", "ftp");

    /** Cleans the body markup of a report. */
    public String sanitizeBody(String html) {
        Document dirty = Jsoup.parseBodyFragment(html == null ? "" : html);
        Document clean = new org.jsoup.safety.Cleaner(safelist).clean(dirty);
        for (Element element : clean.select("[style]")) {
            element.attr("style", sanitizeCss(element.attr("style")));
        }
        clean.outputSettings().prettyPrint(false);
        return clean.body().html();
    }

    /** Removes from CSS what could load a resource or run code. */
    public String sanitizeCss(String css) {
        if (css == null) return "";
        String out = CSS_IMPORT.matcher(css).replaceAll("");
        out = CSS_URL.matcher(out).replaceAll("none");
        Matcher dangerous = CSS_DANGEROUS.matcher(out);
        out = dangerous.replaceAll("");
        // The stylesheet goes inside <style>; a '<' could close it and open markup.
        return out.replace("<", "");
    }

    /** A complete, standalone HTML document: what the preview shows and the PDF will print. */
    public String document(String title, String sanitizedBody, String sanitizedCss) {
        String safeTitle = title == null ? "" : org.jsoup.nodes.Entities.escape(title);
        return "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n<title>" + safeTitle + "</title>\n"
                + "<style>\n" + sanitizedCss + "\n</style>\n</head>\n<body>\n" + sanitizedBody + "\n</body>\n</html>\n";
    }
}
